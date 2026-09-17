package main

import (
	"io"
	"net"
	"testing"
	"time"
)

// fakeSocks runs a scripted SOCKS5 server: method selection, auth verdict,
// CONNECT reply. It forwards nothing; success paths just consume BND bytes.
type fakeSocks struct {
	t          *testing.T
	method     byte // method to select (0x00, 0x02, 0xff)
	authOK     bool // auth verdict when method == 0x02
	connectRep byte // CONNECT reply code
	badVersion bool // send wrong version byte
}

func (f *fakeSocks) serve(ln net.Listener) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go f.handle(conn)
	}
}

func (f *fakeSocks) handle(conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		return
	}
	nmethods := int(hdr[1])
	if _, err := io.CopyN(io.Discard, conn, int64(nmethods)); err != nil {
		return
	}
	ver := byte(0x05)
	if f.badVersion {
		ver = 0x04
	}
	if _, err := conn.Write([]byte{ver, f.method}); err != nil {
		return
	}
	if f.method == 0xff || f.badVersion {
		return
	}
	if f.method == 0x02 {
		// Read RFC 1929 request: ver, ulen, user, plen, pass.
		fixed := make([]byte, 2)
		if _, err := io.ReadFull(conn, fixed); err != nil {
			return
		}
		ulen := int(fixed[1])
		if _, err := io.CopyN(io.Discard, conn, int64(ulen)); err != nil {
			return
		}
		plen := make([]byte, 1)
		if _, err := io.ReadFull(conn, plen); err != nil {
			return
		}
		if _, err := io.CopyN(io.Discard, conn, int64(plen[0])); err != nil {
			return
		}
		st := byte(0x00)
		if !f.authOK {
			st = 0x01
		}
		if _, err := conn.Write([]byte{0x01, st}); err != nil {
			return
		}
		if !f.authOK {
			return
		}
	}
	// Read CONNECT request: 4 header + domain form.
	creq := make([]byte, 4)
	if _, err := io.ReadFull(conn, creq); err != nil {
		return
	}
	if creq[3] == 0x03 {
		lb := make([]byte, 1)
		if _, err := io.ReadFull(conn, lb); err != nil {
			return
		}
		if _, err := io.CopyN(io.Discard, conn, int64(lb[0])+2); err != nil {
			return
		}
	} else {
		return
	}
	_, _ = conn.Write([]byte{0x05, f.connectRep, 0x00, 0x01, 127, 0, 0, 1, 0, 80})
	// Hold open briefly so the client can finish reading BND.ADDR.
	time.Sleep(200 * time.Millisecond)
}

func startFake(t *testing.T, f *fakeSocks) (host string, port int, stop func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	f.t = t
	go f.serve(ln)
	addr := ln.Addr().(*net.TCPAddr)
	return "127.0.0.1", addr.Port, func() { ln.Close() }
}

func TestProbeOK(t *testing.T) {
	host, port, stop := startFake(t, &fakeSocks{method: 0x02, authOK: true, connectRep: 0x00})
	defer stop()
	res := ProbeSocks5(host, port, "u", "p", "google.com", 80, 5*time.Second)
	if res.Outcome != ProbeOK {
		t.Fatalf("want OK, got %s (%s)", res.Outcome, res.Detail)
	}
	if probeDisplay(res.Outcome) != "Proxy works" {
		t.Fatalf("bad display string: %q", probeDisplay(res.Outcome))
	}
}

func TestProbeNoAuth(t *testing.T) {
	host, port, stop := startFake(t, &fakeSocks{method: 0x00, connectRep: 0x00})
	defer stop()
	res := ProbeSocks5(host, port, "", "", "google.com", 80, 5*time.Second)
	if res.Outcome != ProbeOK {
		t.Fatalf("want OK, got %s (%s)", res.Outcome, res.Detail)
	}
	if res.Phases.Auth != 0 {
		t.Fatalf("auth phase should be zero for no-auth, got %v", res.Phases.Auth)
	}
}

func TestProbeAuthFailed(t *testing.T) {
	host, port, stop := startFake(t, &fakeSocks{method: 0x02, authOK: false})
	defer stop()
	res := ProbeSocks5(host, port, "u", "wrong", "google.com", 80, 5*time.Second)
	if res.Outcome != ProbeAuthFailed {
		t.Fatalf("want AUTH_FAILED, got %s (%s)", res.Outcome, res.Detail)
	}
}

func TestProbeNotSocks5(t *testing.T) {
	host, port, stop := startFake(t, &fakeSocks{method: 0xff})
	defer stop()
	res := ProbeSocks5(host, port, "u", "p", "google.com", 80, 5*time.Second)
	if res.Outcome != ProbeNotSocks5 {
		t.Fatalf("want NOT_SOCKS5, got %s (%s)", res.Outcome, res.Detail)
	}

	host2, port2, stop2 := startFake(t, &fakeSocks{badVersion: true})
	defer stop2()
	res2 := ProbeSocks5(host2, port2, "u", "p", "google.com", 80, 5*time.Second)
	if res2.Outcome != ProbeNotSocks5 {
		t.Fatalf("want NOT_SOCKS5 for bad version, got %s", res2.Outcome)
	}
}

func TestProbeConnectFailed(t *testing.T) {
	host, port, stop := startFake(t, &fakeSocks{method: 0x00, connectRep: 0x05})
	defer stop()
	res := ProbeSocks5(host, port, "", "", "google.com", 80, 5*time.Second)
	if res.Outcome != ProbeConnectFailed {
		t.Fatalf("want CONNECT_FAILED, got %s (%s)", res.Outcome, res.Detail)
	}
}

func TestProbeUnreachable(t *testing.T) {
	// Closed port on loopback: connection refused.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	res := ProbeSocks5("127.0.0.1", port, "u", "p", "google.com", 80, 2*time.Second)
	if res.Outcome != ProbeUnreachable {
		t.Fatalf("want UNREACHABLE, got %s (%s)", res.Outcome, res.Detail)
	}

	res2 := ProbeSocks5("", 1080, "u", "p", "google.com", 80, 2*time.Second)
	if res2.Outcome != ProbeUnreachable {
		t.Fatalf("want UNREACHABLE for empty host, got %s", res2.Outcome)
	}
}
