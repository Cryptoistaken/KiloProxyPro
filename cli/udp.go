// Pro-only: SOCKS5 UDP ASSOCIATE relay test with a real DNS query.
//
// Mirrors the hev Fast-tunnel UDP path (PREF_HEV_UDP=true, hev.yml
// "udp: 'udp'"): greet/auth like the stock handshake, UDP ASSOCIATE,
// then a DNS A query for a name sent through the relay to a resolver
// (default 8.8.8.8, the profile-DNS fallback). A TCP-only proxy refuses
// the ASSOCIATE or drops the datagrams; that is exactly the case where
// the app must fall back to UDP-over-TCP (PREF_HEV_UDP=false) and
// direct DNS via the route carve-out.
package main

import (
	"encoding/binary"
	"fmt"
	"io"
	"math/rand"
	"net"
	"strings"
	"time"
)

// UDP outcomes.
const (
	UdpOK          = "UDP_OK"
	UdpUnsupported = "UDP_UNSUPPORTED"
	UdpTimeout     = "UDP_TIMEOUT"
	UdpUnreachable = "UNREACHABLE"
)

func udpDisplay(outcome string) string {
	switch outcome {
	case UdpOK:
		return "UDP relay works"
	case UdpTimeout:
		return "UDP relay timed out"
	case UdpUnreachable:
		return "Proxy unreachable"
	default:
		return "No UDP support"
	}
}

// UdpResult is the outcome of one UDP ASSOCIATE relay test.
type UdpResult struct {
	Outcome string // one of Udp* constants
	answers []string
	Elapsed time.Duration // associate + query round trip
	Detail  string
}

// Answers returns the resolved IPv4/IPv6 strings on success.
func (r UdpResult) Answers() []string { return r.answers }

// UdpAssociateTest runs greet/auth, UDP ASSOCIATE, and one DNS A query
// for qname through the relay to resolverIP:53. timeout bounds the TCP
// phases; udpWait bounds the datagram round trip.
func UdpAssociateTest(proxyHost string, proxyPort int, user, pass, qname, resolverIP string, timeout, udpWait time.Duration) UdpResult {
	var res UdpResult
	fail := func(outcome, detail string) UdpResult {
		res.Outcome, res.Detail = outcome, detail
		return res
	}
	if proxyHost == "" {
		return fail(UdpUnreachable, "empty proxy host")
	}
	if resolverIP == "" {
		resolverIP = "8.8.8.8"
	}
	start := time.Now()

	tcp, err := net.DialTimeout("tcp", net.JoinHostPort(proxyHost, itoa(proxyPort)), timeout)
	if err != nil {
		return fail(UdpUnreachable, err.Error())
	}
	defer tcp.Close()
	_ = tcp.SetDeadline(time.Now().Add(timeout))

	if _, err := tcp.Write([]byte{0x05, 0x01, 0x02}); err != nil {
		return fail(UdpUnreachable, "greet write: "+err.Error())
	}
	greet := make([]byte, 2)
	if _, err := io.ReadFull(tcp, greet); err != nil {
		return fail(UdpUnreachable, "greet read: "+err.Error())
	}
	if greet[0] != 0x05 || greet[1] == 0xff {
		return fail(UdpUnsupported, "no acceptable auth method")
	}
	if greet[1] == 0x02 {
		u, p := []byte(user), []byte(pass)
		req := make([]byte, 0, 3+len(u)+len(p))
		req = append(req, 0x01, byte(len(u)))
		req = append(req, u...)
		req = append(req, byte(len(p)))
		req = append(req, p...)
		if _, err := tcp.Write(req); err != nil {
			return fail(UdpUnreachable, "auth write: "+err.Error())
		}
		st := make([]byte, 2)
		if _, err := io.ReadFull(tcp, st); err != nil {
			return fail(UdpUnreachable, "auth read: "+err.Error())
		}
		if st[1] != 0x00 {
			return fail(UdpUnreachable, "proxy authentication failed")
		}
	}

	// UDP ASSOCIATE to 0.0.0.0:0, let the server pick the relay port.
	if _, err := tcp.Write([]byte{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return fail(UdpUnreachable, "associate write: "+err.Error())
	}
	hdr := make([]byte, 4)
	if _, err := io.ReadFull(tcp, hdr); err != nil {
		return fail(UdpUnreachable, "associate read: "+err.Error())
	}
	if hdr[1] != 0x00 {
		return fail(UdpUnsupported, fmt.Sprintf("associate refused (reply 0x%02x)", hdr[1]))
	}
	relay, err := readSocksAddr(tcp, hdr[3])
	if err != nil {
		return fail(UdpUnreachable, "relay addr read: "+err.Error())
	}
	relayHost := relay.host
	if relayHost == "0.0.0.0" || relayHost == "::" || relayHost == "" {
		// RFC 1928: unspecified BND.ADDR means the proxy's own address.
		relayHost = proxyHost
	}

	udp, err := net.DialTimeout("udp", net.JoinHostPort(relayHost, itoa(relay.port)), timeout)
	if err != nil {
		return fail(UdpUnsupported, "relay dial: "+err.Error())
	}
	defer udp.Close()

	query, qid := buildDNSQuery(qname)
	dgram := wrapUDPv4(query, resolverIP, 53)
	_ = udp.SetDeadline(time.Now().Add(udpWait))
	if _, err := udp.Write(dgram); err != nil {
		return fail(UdpUnsupported, "relay write: "+err.Error())
	}
	buf := make([]byte, 4096)
	n, err := udp.Read(buf)
	if err != nil {
		if ne, ok := err.(net.Error); ok && ne.Timeout() {
			return fail(UdpTimeout, "no datagram back within "+udpWait.String()+" (TCP-only proxy?)")
		}
		return fail(UdpUnsupported, "relay read: "+err.Error())
	}
	payload, err := unwrapUDP(buf[:n])
	if err != nil {
		return fail(UdpUnsupported, "relay framing: "+err.Error())
	}
	addrs, err := parseDNSResponse(payload, qid)
	if err != nil {
		// Datagrams flow but the DNS payload is odd; the relay itself works.
		res.Outcome = UdpOK
		res.Detail = "relay works, dns parse: " + err.Error()
		res.Elapsed = time.Since(start)
		return res
	}
	res.Outcome = UdpOK
	res.answers = addrs
	res.Elapsed = time.Since(start)
	return res
}

type socksAddr struct {
	host string
	port int
}

// readSocksAddr reads BND.ADDR+BND.PORT for the given ATYP.
func readSocksAddr(r io.Reader, atyp byte) (socksAddr, error) {
	var a socksAddr
	switch atyp {
	case 0x01:
		b := make([]byte, 6)
		if _, err := io.ReadFull(r, b); err != nil {
			return a, err
		}
		a.host = net.IP(b[:4]).String()
		a.port = int(b[4])<<8 | int(b[5])
	case 0x04:
		b := make([]byte, 18)
		if _, err := io.ReadFull(r, b); err != nil {
			return a, err
		}
		a.host = net.IP(b[:16]).String()
		a.port = int(b[16])<<8 | int(b[17])
	case 0x03:
		lb := make([]byte, 1)
		if _, err := io.ReadFull(r, lb); err != nil {
			return a, err
		}
		b := make([]byte, int(lb[0])+2)
		if _, err := io.ReadFull(r, b); err != nil {
			return a, err
		}
		a.host = string(b[:len(b)-2])
		a.port = int(b[len(b)-2])<<8 | int(b[len(b)-1])
	default:
		return a, fmt.Errorf("bad atyp 0x%02x", atyp)
	}
	return a, nil
}

// wrapUDPv4 frames payload for an IPv4 dstIP:dstPort relay target.
func wrapUDPv4(payload []byte, dstIP string, dstPort int) []byte {
	ip := net.ParseIP(dstIP).To4()
	out := make([]byte, 0, 10+len(payload))
	out = append(out, 0x00, 0x00, 0x00, 0x01)
	out = append(out, ip...)
	out = append(out, byte(dstPort>>8), byte(dstPort))
	return append(out, payload...)
}

// unwrapUDP strips the relay header, requiring FRAG=0.
func unwrapUDP(dgram []byte) ([]byte, error) {
	if len(dgram) < 4 {
		return nil, fmt.Errorf("short datagram (%d bytes)", len(dgram))
	}
	if dgram[2] != 0x00 {
		return nil, fmt.Errorf("fragmented reply (frag=%d)", dgram[2])
	}
	off := 4
	switch dgram[3] {
	case 0x01:
		off += 4 + 2
	case 0x04:
		off += 16 + 2
	case 0x03:
		if len(dgram) < 5 {
			return nil, fmt.Errorf("short domain header")
		}
		off += 1 + int(dgram[4]) + 2
	default:
		return nil, fmt.Errorf("bad atyp 0x%02x", dgram[3])
	}
	if off > len(dgram) {
		return nil, fmt.Errorf("header overruns datagram")
	}
	return dgram[off:], nil
}

// buildDNSQuery encodes a minimal DNS A query; returns wire bytes and ID.
func buildDNSQuery(name string) ([]byte, uint16) {
	qid := uint16(rand.Int31n(65536))
	out := make([]byte, 12, 32)
	binary.BigEndian.PutUint16(out[0:], qid)
	binary.BigEndian.PutUint16(out[2:], 0x0100) // RD
	binary.BigEndian.PutUint16(out[4:], 1)      // QDCOUNT
	for _, label := range strings.Split(strings.TrimSuffix(name, "."), ".") {
		out = append(out, byte(len(label)))
		out = append(out, label...)
	}
	out = append(out, 0x00)
	qtype := make([]byte, 4)
	binary.BigEndian.PutUint16(qtype[0:], 1) // A
	binary.BigEndian.PutUint16(qtype[2:], 1) // IN
	return append(out, qtype...), qid
}

// parseDNSResponse extracts A/AAAA answer RDATA for the query ID.
func parseDNSResponse(msg []byte, qid uint16) ([]string, error) {
	if len(msg) < 12 {
		return nil, fmt.Errorf("short dns message")
	}
	if binary.BigEndian.Uint16(msg[0:]) != qid {
		return nil, fmt.Errorf("id mismatch")
	}
	if msg[2]&0x80 == 0 {
		return nil, fmt.Errorf("not a response")
	}
	if msg[3]&0x0f != 0 {
		return nil, fmt.Errorf("dns rcode %d", msg[3]&0x0f)
	}
	qd := int(binary.BigEndian.Uint16(msg[4:]))
	an := int(binary.BigEndian.Uint16(msg[6:]))
	off := 12
	for i := 0; i < qd; i++ {
		var err error
		if off, err = skipDNSName(msg, off); err != nil {
			return nil, err
		}
		off += 4
	}
	var out []string
	for i := 0; i < an; i++ {
		var err error
		if off, err = skipDNSName(msg, off); err != nil {
			return nil, err
		}
		if off+10 > len(msg) {
			return nil, fmt.Errorf("short rr header")
		}
		typ := binary.BigEndian.Uint16(msg[off:])
		rdlen := int(binary.BigEndian.Uint16(msg[off+8:]))
		off += 10
		if off+rdlen > len(msg) {
			return nil, fmt.Errorf("rr overruns message")
		}
		if (typ == 1 && rdlen == 4) || (typ == 28 && rdlen == 16) {
			out = append(out, net.IP(msg[off:off+rdlen]).String())
		}
		off += rdlen
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("no A/AAAA answers")
	}
	return out, nil
}

// skipDNSName advances past a possibly compressed domain name.
func skipDNSName(msg []byte, off int) (int, error) {
	for {
		if off >= len(msg) {
			return 0, fmt.Errorf("name overruns message")
		}
		l := int(msg[off])
		if l&0xc0 == 0xc0 {
			return off + 2, nil
		}
		if l == 0 {
			return off + 1, nil
		}
		off += 1 + l
	}
}
