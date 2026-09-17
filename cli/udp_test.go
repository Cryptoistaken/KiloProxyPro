package main

import (
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

// fakeRelay is a scripted UDP ASSOCIATE server: TCP handshake with
// optional auth, ASSOCIATE reply pointing at its UDP socket, then a
// canned DNS A response for any query.
type fakeRelay struct {
	t           *testing.T
	associateOK bool
	auth        bool
	cannedIP    net.IP
}

func (f *fakeRelay) runTCP(ln net.Listener, udpPort int) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go func(c net.Conn) {
			defer c.Close()
			_ = c.SetDeadline(time.Now().Add(5 * time.Second))
			hdr := make([]byte, 2)
			if _, err := io.ReadFull(c, hdr); err != nil {
				return
			}
			if _, err := io.CopyN(io.Discard, c, int64(hdr[1])); err != nil {
				return
			}
			method := byte(0x00)
			if f.auth {
				method = 0x02
			}
			if _, err := c.Write([]byte{0x05, method}); err != nil {
				return
			}
			if method == 0x02 {
				fixed := make([]byte, 2)
				if _, err := io.ReadFull(c, fixed); err != nil {
					return
				}
				if _, err := io.CopyN(io.Discard, c, int64(fixed[1])); err != nil {
					return
				}
				pl := make([]byte, 1)
				if _, err := io.ReadFull(c, pl); err != nil {
					return
				}
				if _, err := io.CopyN(io.Discard, c, int64(pl[0])); err != nil {
					return
				}
				if _, err := c.Write([]byte{0x01, 0x00}); err != nil {
					return
				}
			}
			assoc := make([]byte, 10)
			if _, err := io.ReadFull(c, assoc); err != nil {
				return
			}
			if !f.associateOK {
				_, _ = c.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
				return
			}
			_, _ = c.Write([]byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, byte(udpPort >> 8), byte(udpPort)})
			// Hold the TCP conn open (required for the association lifetime).
			time.Sleep(2 * time.Second)
		}(conn)
	}
}

func startFakeRelay(t *testing.T, f *fakeRelay) (host string, port int, stop func()) {
	t.Helper()
	udpConn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	udpPort := udpConn.LocalAddr().(*net.UDPAddr).Port
	f.t = t
	go func() {
		buf := make([]byte, 4096)
		for {
			n, addr, err := udpConn.ReadFrom(buf)
			if err != nil {
				return
			}
			payload, err := unwrapUDP(buf[:n])
			if err != nil || len(payload) < 12 {
				continue
			}
			qid := binary.BigEndian.Uint16(payload[:2])
			resp := cannedDNSResponse(payload, qid, f.cannedIP)
			_, _ = udpConn.WriteTo(wrapUDPv4(resp, "127.0.0.1", 9999), addr)
		}
	}()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go f.runTCP(ln, udpPort)
	return "127.0.0.1", ln.Addr().(*net.TCPAddr).Port, func() {
		ln.Close()
		udpConn.Close()
	}
}

// cannedDNSResponse answers any query with one A record.
func cannedDNSResponse(query []byte, qid uint16, ip net.IP) []byte {
	out := make([]byte, 12, len(query)+16)
	binary.BigEndian.PutUint16(out[0:], qid)
	binary.BigEndian.PutUint16(out[2:], 0x8180)
	binary.BigEndian.PutUint16(out[4:], 1)
	binary.BigEndian.PutUint16(out[6:], 1)
	out = append(out, query[12:]...) // question section
	out = append(out, 0xc0, 0x0c)    // name pointer
	rr := make([]byte, 10)
	binary.BigEndian.PutUint16(rr[0:], 1)
	binary.BigEndian.PutUint16(rr[2:], 1)
	binary.BigEndian.PutUint32(rr[4:], 60)
	binary.BigEndian.PutUint16(rr[8:], 4)
	out = append(out, rr...)
	return append(out, ip.To4()...)
}

func TestUdpAssociateOK(t *testing.T) {
	host, port, stop := startFakeRelay(t, &fakeRelay{associateOK: true, cannedIP: net.ParseIP("203.0.113.9")})
	defer stop()
	res := UdpAssociateTest(host, port, "", "", "example.com", "9.9.9.9", 5*time.Second, 5*time.Second)
	if res.Outcome != UdpOK {
		t.Fatalf("want UDP_OK, got %s (%s)", res.Outcome, res.Detail)
	}
	if len(res.Answers()) != 1 || res.Answers()[0] != "203.0.113.9" {
		t.Fatalf("bad answers: %v", res.Answers())
	}
	if udpDisplay(res.Outcome) != "UDP relay works" {
		t.Fatalf("bad display: %q", udpDisplay(res.Outcome))
	}
}

func TestUdpAssociateRefused(t *testing.T) {
	host, port, stop := startFakeRelay(t, &fakeRelay{associateOK: false})
	defer stop()
	res := UdpAssociateTest(host, port, "", "", "example.com", "9.9.9.9", 5*time.Second, 2*time.Second)
	if res.Outcome != UdpUnsupported {
		t.Fatalf("want UDP_UNSUPPORTED, got %s (%s)", res.Outcome, res.Detail)
	}
}

func TestUdpAssociateTimeout(t *testing.T) {
	// Relay accepts ASSOCIATE but never answers datagrams.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	udpConn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer udpConn.Close()
	udpPort := udpConn.LocalAddr().(*net.UDPAddr).Port
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_ = c.SetDeadline(time.Now().Add(5 * time.Second))
				hdr := make([]byte, 2)
				if _, err := io.ReadFull(c, hdr); err != nil {
					return
				}
				_, _ = io.CopyN(io.Discard, c, int64(hdr[1]))
				_, _ = c.Write([]byte{0x05, 0x00})
				assoc := make([]byte, 10)
				if _, err := io.ReadFull(c, assoc); err != nil {
					return
				}
				_, _ = c.Write([]byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, byte(udpPort >> 8), byte(udpPort)})
				time.Sleep(2 * time.Second)
			}(c)
		}
	}()
	res := UdpAssociateTest("127.0.0.1", ln.Addr().(*net.TCPAddr).Port, "", "", "example.com", "9.9.9.9", 5*time.Second, 500*time.Millisecond)
	if res.Outcome != UdpTimeout {
		t.Fatalf("want UDP_TIMEOUT, got %s (%s)", res.Outcome, res.Detail)
	}
}

func TestDNSCodecRoundTrip(t *testing.T) {
	q, qid := buildDNSQuery("www.example.com")
	resp := cannedDNSResponse(q, qid, net.ParseIP("198.51.100.3"))
	addrs, err := parseDNSResponse(resp, qid)
	if err != nil {
		t.Fatal(err)
	}
	if len(addrs) != 1 || addrs[0] != "198.51.100.3" {
		t.Fatalf("bad round trip: %v", addrs)
	}
	if _, err := parseDNSResponse(resp, qid+1); err == nil {
		t.Fatal("want id-mismatch error")
	}
}

func TestUnwrapUDPFrag(t *testing.T) {
	q, _ := buildDNSQuery("example.com")
	d := wrapUDPv4(q, "8.8.8.8", 53)
	d[2] = 0x01
	if _, err := unwrapUDP(d); err == nil {
		t.Fatal("want frag rejection")
	}
}
