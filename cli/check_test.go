package main

import (
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestParseKiloIP(t *testing.T) {
	info := parseKiloIP(`{"ip":"203.0.113.7","countryCode":"DE","country":"Germany","regionName":"Bavaria","city":"Munich","isp":"Example ISP","org":"Example Org","asName":"AS123","timezone":"Europe/Berlin"}`)
	if info == nil {
		t.Fatal("want parsed info, got nil")
	}
	if info.IP != "203.0.113.7" || info.CountryCode != "DE" || info.City != "Munich" || info.ISP != "Example ISP" {
		t.Fatalf("bad parse: %+v", info)
	}
	if parseKiloIP(`not json`) != nil {
		t.Fatal("want nil for garbage")
	}
	if parseKiloIP(`{"countryCode":"DE"}`) != nil {
		t.Fatal("want nil when ip missing")
	}
}

func TestParseTrace(t *testing.T) {
	body := "fl=1\nh=www.cloudflare.com\nip=198.51.100.9\nts=1.0\nvisit_scheme=https\nloc=US\n"
	info := parseTrace(body)
	if info == nil {
		t.Fatal("want parsed info, got nil")
	}
	if info.IP != "198.51.100.9" || info.CountryCode != "US" {
		t.Fatalf("bad parse: %+v", info)
	}
	if parseTrace("fl=1\nloc=US\n") != nil {
		t.Fatal("want nil when ip missing")
	}
}

// TestCheckDirect exercises fetchCheck against local HTTP servers (no proxy).
func TestCheckDirect(t *testing.T) {
	kilo := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"ip":"203.0.113.7","countryCode":"DE"}`))
	}))
	defer kilo.Close()
	trace := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("ip=198.51.100.9\nloc=US\n"))
	}))
	defer trace.Close()

	client := checkerClient("", 0, "", "", 5*time.Second)
	kr := fetchCheck(client, kilo.URL, "kiloip", false, parseKiloIP)
	if kr.Info == nil || kr.Info.IP != "203.0.113.7" {
		t.Fatalf("bad kiloip fetch: %+v err=%s", kr.Info, kr.Err)
	}
	tr := fetchCheck(client, trace.URL, "trace", false, parseTrace)
	if tr.Info == nil || tr.Info.IP != "198.51.100.9" {
		t.Fatalf("bad trace fetch: %+v err=%s", tr.Info, tr.Err)
	}

	// HTTP error status must fail, like the engine's null return.
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(500)
	}))
	defer bad.Close()
	br := fetchCheck(client, bad.URL, "kiloip", false, parseKiloIP)
	if br.Info != nil {
		t.Fatalf("want nil info on http 500, got %+v", br.Info)
	}
}

// forwardingSocks is a minimal CONNECT proxy that forwards to real TCP
// targets so the via-proxy HTTP path is tested end to end.
type forwardingSocks struct {
	requireAuth bool
}

func (f *forwardingSocks) serve(ln net.Listener) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go func(c net.Conn) {
			defer c.Close()
			_ = c.SetDeadline(time.Now().Add(10 * time.Second))
			if err := serveForward(c, f.requireAuth); err != nil {
				return
			}
		}(conn)
	}
}

func serveForward(client net.Conn, requireAuth bool) error {
	buf := make([]byte, 257)
	if _, err := readN(client, buf[:2]); err != nil {
		return err
	}
	if _, err := readN(client, buf[:int(buf[1])]); err != nil {
		return err
	}
	method := byte(0x00)
	if requireAuth {
		method = 0x02
	}
	if _, err := client.Write([]byte{0x05, method}); err != nil {
		return err
	}
	if method == 0x02 {
		if _, err := readN(client, buf[:2]); err != nil {
			return err
		}
		ulen := int(buf[1])
		if _, err := readN(client, buf[:ulen]); err != nil {
			return err
		}
		user := string(buf[:ulen])
		if _, err := readN(client, buf[:1]); err != nil {
			return err
		}
		plen := int(buf[0])
		if _, err := readN(client, buf[:plen]); err != nil {
			return err
		}
		pass := string(buf[:plen])
		st := byte(0x00)
		if user != "u" || pass != "p" {
			st = 0x01
		}
		if _, err := client.Write([]byte{0x01, st}); err != nil {
			return err
		}
		if st != 0x00 {
			return errAuth
		}
	}
	hdr := make([]byte, 4)
	if _, err := readN(client, hdr); err != nil {
		return err
	}
	var target string
	switch hdr[3] {
	case 0x01:
		b := make([]byte, 6)
		if _, err := readN(client, b); err != nil {
			return err
		}
		target = (&net.TCPAddr{IP: b[:4], Port: int(b[4])<<8 | int(b[5])}).String()
	case 0x03:
		if _, err := readN(client, buf[:1]); err != nil {
			return err
		}
		l := int(buf[0])
		b := make([]byte, l+2)
		if _, err := readN(client, b); err != nil {
			return err
		}
		host := string(b[:l])
		port := int(b[l])<<8 | int(b[l+1])
		target = net.JoinHostPort(host, itoa(port))
	default:
		return errAtyp
	}
	up, err := net.DialTimeout("tcp", target, 5*time.Second)
	if err != nil {
		_, _ = client.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return err
	}
	defer up.Close()
	_ = up.SetDeadline(time.Now().Add(10 * time.Second))
	if _, err := client.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return err
	}
	_ = client.SetDeadline(time.Time{})
	_ = up.SetDeadline(time.Time{})
	go copyBytes(up, client)
	copyBytes(client, up)
	return nil
}

var errAuth = errTest("auth failed")
var errAtyp = errTest("bad atyp")

type errTest string

func (e errTest) Error() string { return string(e) }

func readN(c net.Conn, b []byte) (int, error) {
	got := 0
	for got < len(b) {
		n, err := c.Read(b[got:])
		if err != nil {
			return got, err
		}
		got += n
	}
	return got, nil
}

func copyBytes(dst, src net.Conn) {
	b := make([]byte, 32*1024)
	for {
		n, err := src.Read(b)
		if n > 0 {
			if _, werr := dst.Write(b[:n]); werr != nil {
				return
			}
		}
		if err != nil {
			return
		}
	}
}

// TestCheckViaProxy runs a full HTTP fetch through the forwarding proxy.
func TestCheckViaProxy(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"ip":"203.0.113.7","countryCode":"DE"}`))
	}))
	defer srv.Close()

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	fw := &forwardingSocks{requireAuth: true}
	go fw.serve(ln)
	pport := ln.Addr().(*net.TCPAddr).Port

	client := checkerClient("127.0.0.1", pport, "u", "p", 5*time.Second)
	r := fetchCheck(client, srv.URL, "kiloip", true, parseKiloIP)
	if r.Info == nil || r.Info.IP != "203.0.113.7" {
		t.Fatalf("bad via-proxy fetch: %+v err=%s", r.Info, r.Err)
	}

	// Wrong password must fail the fetch.
	badClient := checkerClient("127.0.0.1", pport, "u", "wrong", 5*time.Second)
	br := fetchCheck(badClient, srv.URL, "kiloip", true, parseKiloIP)
	if br.Info != nil {
		t.Fatalf("want nil info with bad creds, got %+v", br.Info)
	}
	if !strings.Contains(br.Err, "authentication") {
		t.Fatalf("want auth error, got %q", br.Err)
	}
}

func TestResolveLocalhost(t *testing.T) {
	chosen, all, _, err := ResolveHost("localhost", 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if chosen == "" || len(all) == 0 {
		t.Fatalf("want addresses, got chosen=%q all=%v", chosen, all)
	}
	// Engine prefers IPv4 when present.
	hasV4 := false
	for _, a := range all {
		if strings.Count(a, ".") == 3 {
			hasV4 = true
		}
	}
	if hasV4 && strings.Count(chosen, ".") != 3 {
		t.Fatalf("want IPv4 preferred, chosen=%q all=%v", chosen, all)
	}
}
