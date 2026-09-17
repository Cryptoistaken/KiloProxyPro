// Package main implements the SOCKS5 handshake probe with exact
// SocksTester.kt parity: method negotiation offering only 0x02
// (username/password), RFC 1929 auth, then CONNECT to a test target in
// domain form. Outcomes use the same classification and display strings
// as the app so CLI and app results are directly comparable.
package main

import (
	"fmt"
	"io"
	"net"
	"time"
)

// Probe outcomes, mirroring SocksTester.ProxyProbe.
const (
	ProbeOK            = "OK"
	ProbeAuthFailed    = "AUTH_FAILED"
	ProbeNotSocks5     = "NOT_SOCKS5"
	ProbeConnectFailed = "CONNECT_FAILED"
	ProbeUnreachable   = "UNREACHABLE"
)

// probeDisplay mirrors SocksTester.testProxy status strings (plain ASCII).
func probeDisplay(outcome string) string {
	switch outcome {
	case ProbeOK:
		return "Proxy works"
	case ProbeAuthFailed:
		return "Auth failed"
	case ProbeNotSocks5:
		return "Not a SOCKS5 proxy"
	case ProbeConnectFailed:
		return "Connection failed"
	default:
		return "Proxy unreachable"
	}
}

// ProbePhases holds per-phase timings of one handshake probe.
type ProbePhases struct {
	TCP       time.Duration // TCP dial to the proxy
	Handshake time.Duration // method negotiation (greet + response)
	Auth      time.Duration // RFC 1929 auth exchange (0 when no-auth)
	Connect   time.Duration // CONNECT request + full reply
}

// ProbeResult is the outcome of one SOCKS5 handshake probe.
type ProbeResult struct {
	Outcome string // one of Probe* constants
	Phases  ProbePhases
	Detail  string // low-level error for UNREACHABLE, else ""
}

// ProbeSocks5 performs the raw-socket SOCKS5 handshake against
// proxyHost:proxyPort and CONNECTs to targetHost:targetPort.
// timeout mirrors the engine (5s dial + 5s read/write).
func ProbeSocks5(proxyHost string, proxyPort int, user, pass, targetHost string, targetPort int, timeout time.Duration) ProbeResult {
	var res ProbeResult
	fail := func(outcome, detail string) ProbeResult {
		res.Outcome = outcome
		res.Detail = detail
		return res
	}
	if proxyHost == "" {
		return fail(ProbeUnreachable, "empty proxy host")
	}

	start := time.Now()
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(proxyHost, itoa(proxyPort)), timeout)
	res.Phases.TCP = time.Since(start)
	if err != nil {
		return fail(ProbeUnreachable, err.Error())
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(timeout))

	// SOCKS5 method negotiation: offer username/password only, like the engine.
	start = time.Now()
	if _, err := conn.Write([]byte{0x05, 0x01, 0x02}); err != nil {
		return fail(ProbeUnreachable, "greet write: "+err.Error())
	}
	authResp := make([]byte, 2)
	if _, err := io.ReadFull(conn, authResp); err != nil {
		return fail(ProbeUnreachable, "greet read: "+err.Error())
	}
	res.Phases.Handshake = time.Since(start)
	if authResp[0] != 0x05 {
		return fail(ProbeNotSocks5, fmt.Sprintf("bad version 0x%02x", authResp[0]))
	}
	if authResp[1] == 0xff {
		return fail(ProbeNotSocks5, "no acceptable auth method")
	}

	// Username/password auth (RFC 1929), only when the server selects it.
	if authResp[1] == 0x02 {
		start = time.Now()
		u, p := []byte(user), []byte(pass)
		req := make([]byte, 0, 3+len(u)+len(p))
		req = append(req, 0x01, byte(len(u)))
		req = append(req, u...)
		req = append(req, byte(len(p)))
		req = append(req, p...)
		if _, err := conn.Write(req); err != nil {
			return fail(ProbeUnreachable, "auth write: "+err.Error())
		}
		authResp2 := make([]byte, 2)
		if _, err := io.ReadFull(conn, authResp2); err != nil {
			return fail(ProbeUnreachable, "auth read: "+err.Error())
		}
		res.Phases.Auth = time.Since(start)
		if authResp2[1] != 0x00 {
			return fail(ProbeAuthFailed, fmt.Sprintf("auth status 0x%02x", authResp2[1]))
		}
	}

	// CONNECT to the test target in domain form, like the engine.
	start = time.Now()
	host := []byte(targetHost)
	req := make([]byte, 0, 7+len(host))
	req = append(req, 0x05, 0x01, 0x00, 0x03, byte(len(host)))
	req = append(req, host...)
	req = append(req, byte(targetPort>>8), byte(targetPort))
	if _, err := conn.Write(req); err != nil {
		return fail(ProbeUnreachable, "connect write: "+err.Error())
	}
	resp := make([]byte, 4)
	if _, err := io.ReadFull(conn, resp); err != nil {
		return fail(ProbeUnreachable, "connect read: "+err.Error())
	}
	if resp[1] != 0x00 {
		return fail(ProbeConnectFailed, fmt.Sprintf("reply 0x%02x", resp[1]))
	}

	// Consume BND.ADDR + BND.PORT per address type.
	var remaining int
	switch resp[3] {
	case 0x01:
		remaining = 4 + 2
	case 0x04:
		remaining = 16 + 2
	case 0x03:
		lenByte := make([]byte, 1)
		if _, err := io.ReadFull(conn, lenByte); err != nil {
			return fail(ProbeUnreachable, "bnd.addr len read: "+err.Error())
		}
		remaining = int(lenByte[0]) + 2
	default:
		return fail(ProbeNotSocks5, fmt.Sprintf("bad atyp 0x%02x", resp[3]))
	}
	if _, err := io.CopyN(io.Discard, conn, int64(remaining)); err != nil {
		return fail(ProbeUnreachable, "bnd.addr read: "+err.Error())
	}
	res.Phases.Connect = time.Since(start)
	res.Outcome = ProbeOK
	return res
}

func itoa(n int) string { return fmt.Sprint(n) }
