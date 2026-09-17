// Exit-IP verification path with Utility.checkWith parity: kiloip
// primary + cdn-cgi/trace fallback ("both" mode) or a single selected
// checker ("single" mode), 8s connect/read timeouts, optional routing
// through a SOCKS5 proxy with auth. Direct mode (empty proxy host)
// measures the baseline without any proxy.
package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

// Checker endpoints, same as Utility.kt.
const (
	kiloIPURL = "https://kiloproxy.traderspopy.workers.dev/"
	traceURL  = "https://www.cloudflare.com/cdn-cgi/trace"
)

// IpInfo mirrors Utility.IpInfo: exit IP plus geo/ISP enrichment.
type IpInfo struct {
	IP          string `json:"ip"`
	CountryCode string `json:"countryCode"`
	Country     string `json:"country"`
	RegionName  string `json:"regionName"`
	City        string `json:"city"`
	ISP         string `json:"isp"`
	Org         string `json:"org"`
	ASName      string `json:"asName"`
	Timezone    string `json:"timezone"`
}

// CheckResult is one checker fetch plus its timing.
type CheckResult struct {
	Tag      string // "kiloip" or "trace"
	Info     *IpInfo
	Elapsed  time.Duration
	Err      string
	ViaProxy bool
}

// parseKiloIP mirrors Utility.parseKiloIp.
func parseKiloIP(text string) *IpInfo {
	var obj map[string]string
	dec := json.NewDecoder(strings.NewReader(text))
	if err := dec.Decode(&obj); err != nil {
		return nil
	}
	if obj["ip"] == "" {
		return nil
	}
	return &IpInfo{
		IP:          obj["ip"],
		CountryCode: obj["countryCode"],
		Country:     obj["country"],
		RegionName:  obj["regionName"],
		City:        obj["city"],
		ISP:         obj["isp"],
		Org:         obj["org"],
		ASName:      obj["asName"],
		Timezone:    obj["timezone"],
	}
}

// parseTrace mirrors Utility.parseTrace (ip= and loc= lines only).
func parseTrace(text string) *IpInfo {
	var ip, loc string
	for _, line := range strings.Split(text, "\n") {
		switch {
		case strings.HasPrefix(line, "ip="):
			ip = strings.TrimSpace(strings.TrimPrefix(line, "ip="))
		case strings.HasPrefix(line, "loc="):
			loc = strings.TrimSpace(strings.TrimPrefix(line, "loc="))
		}
	}
	if ip == "" {
		return nil
	}
	return &IpInfo{IP: ip, CountryCode: loc}
}

// socksDialer dials targetAddr through a SOCKS5 proxy using the same
// handshake as ProbeSocks5 (offer 0x02, RFC 1929 when selected).
func socksDialer(proxyHost string, proxyPort int, user, pass string, timeout time.Duration) func(ctx context.Context, network, addr string) (net.Conn, error) {
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		d := net.Dialer{Timeout: timeout}
		conn, err := d.DialContext(ctx, "tcp", net.JoinHostPort(proxyHost, itoa(proxyPort)))
		if err != nil {
			return nil, err
		}
		if err := socksHandshake(conn, user, pass, addr, timeout); err != nil {
			conn.Close()
			return nil, err
		}
		return conn, nil
	}
}

// socksHandshake runs greet/auth/CONNECT on an open TCP conn to the proxy.
// addr is the final target in "host:port" form.
func socksHandshake(conn net.Conn, user, pass, addr string, timeout time.Duration) error {
	_ = conn.SetDeadline(time.Now().Add(timeout))
	if _, err := conn.Write([]byte{0x05, 0x01, 0x02}); err != nil {
		return fmt.Errorf("greet write: %w", err)
	}
	resp := make([]byte, 2)
	if _, err := io.ReadFull(conn, resp); err != nil {
		return fmt.Errorf("greet read: %w", err)
	}
	if resp[0] != 0x05 || resp[1] == 0xff {
		return fmt.Errorf("not a SOCKS5 proxy (ver=0x%02x method=0x%02x)", resp[0], resp[1])
	}
	if resp[1] == 0x02 {
		u, p := []byte(user), []byte(pass)
		req := make([]byte, 0, 3+len(u)+len(p))
		req = append(req, 0x01, byte(len(u)))
		req = append(req, u...)
		req = append(req, byte(len(p)))
		req = append(req, p...)
		if _, err := conn.Write(req); err != nil {
			return fmt.Errorf("auth write: %w", err)
		}
		st := make([]byte, 2)
		if _, err := io.ReadFull(conn, st); err != nil {
			return fmt.Errorf("auth read: %w", err)
		}
		if st[1] != 0x00 {
			return fmt.Errorf("proxy authentication failed")
		}
	}
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("bad target addr: %w", err)
	}
	var port int
	if _, err := fmt.Sscanf(portStr, "%d", &port); err != nil {
		return fmt.Errorf("bad target port: %w", err)
	}
	hb := []byte(host)
	req := make([]byte, 0, 7+len(hb))
	req = append(req, 0x05, 0x01, 0x00, 0x03, byte(len(hb)))
	req = append(req, hb...)
	req = append(req, byte(port>>8), byte(port))
	if _, err := conn.Write(req); err != nil {
		return fmt.Errorf("connect write: %w", err)
	}
	hdr := make([]byte, 4)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		return fmt.Errorf("connect read: %w", err)
	}
	if hdr[1] != 0x00 {
		return fmt.Errorf("proxy refused connection (reply 0x%02x)", hdr[1])
	}
	var rest int
	switch hdr[3] {
	case 0x01:
		rest = 6
	case 0x04:
		rest = 18
	case 0x03:
		lb := make([]byte, 1)
		if _, err := io.ReadFull(conn, lb); err != nil {
			return fmt.Errorf("bnd.addr read: %w", err)
		}
		rest = int(lb[0]) + 2
	default:
		return fmt.Errorf("bad atyp 0x%02x", hdr[3])
	}
	if _, err := io.CopyN(io.Discard, conn, int64(rest)); err != nil {
		return fmt.Errorf("bnd.addr read: %w", err)
	}
	_ = conn.SetDeadline(time.Time{})
	return nil
}

// checkerClient builds an HTTP client that fetches url directly or through
// the proxy (empty proxyHost = direct baseline).
func checkerClient(proxyHost string, proxyPort int, user, pass string, timeout time.Duration) *http.Client {
	tr := &http.Transport{
		DisableKeepAlives:   true,
		DialContext:         (&net.Dialer{Timeout: timeout}).DialContext,
		TLSHandshakeTimeout: timeout,
	}
	if proxyHost != "" {
		dial := socksDialer(proxyHost, proxyPort, user, pass, timeout)
		tr.DialContext = dial
		tr.DialTLSContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			conn, err := dial(ctx, network, addr)
			if err != nil {
				return nil, err
			}
			host, _, _ := net.SplitHostPort(addr)
			tlsConn := tls.Client(conn, &tls.Config{ServerName: host})
			if err := tlsConn.HandshakeContext(ctx); err != nil {
				conn.Close()
				return nil, err
			}
			return tlsConn, nil
		}
	}
	return &http.Client{Transport: tr, Timeout: 2*timeout + 5*time.Second}
}

// fetchCheck mirrors Utility.fetchCheckText for one checker URL.
func fetchCheck(client *http.Client, url, tag string, viaProxy bool, parse func(string) *IpInfo) (res CheckResult) {
	res.Tag, res.ViaProxy = tag, viaProxy
	start := time.Now()
	defer func() { res.Elapsed = time.Since(start) }()
	resp, err := client.Get(url)
	if err != nil {
		res.Err = err.Error()
		return res
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		res.Err = err.Error()
		return res
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		res.Err = fmt.Sprintf("http %d", resp.StatusCode)
		return res
	}
	res.Info = parse(string(body))
	if res.Info == nil {
		res.Err = "unparseable checker response"
	}
	return res
}

// CheckWith mirrors Utility.checkWith: run primary first, then the other
// checker as enrichment when primary succeeds or fallback when it fails
// (both=true). Returns the primary result plus the optional second result.
func CheckWith(proxyHost string, proxyPort int, user, pass, primary string, both bool, timeout time.Duration) (first, second CheckResult) {
	urls := map[string]string{"kiloip": kiloIPURL, "trace": traceURL}
	parsers := map[string]func(string) *IpInfo{"kiloip": parseKiloIP, "trace": parseTrace}
	other := map[string]string{"kiloip": "trace", "trace": "kiloip"}
	if primary != "kiloip" && primary != "trace" {
		primary = "kiloip"
	}
	client := checkerClient(proxyHost, proxyPort, user, pass, timeout)
	first = fetchCheck(client, urls[primary], primary, proxyHost != "", parsers[primary])
	if !both {
		return first, CheckResult{}
	}
	second = fetchCheck(client, urls[other[primary]], other[primary], proxyHost != "", parsers[other[primary]])
	return first, second
}

// ResolveHost mirrors Utility.resolveHost: prefer IPv4, never fabricate.
func ResolveHost(host string, timeout time.Duration) (chosen string, all []string, elapsed time.Duration, err error) {
	start := time.Now()
	defer func() { elapsed = time.Since(start) }()
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	ips, err := net.DefaultResolver.LookupIP(ctx, "ip", host)
	if err != nil {
		return "", nil, time.Since(start), err
	}
	for _, ip := range ips {
		all = append(all, ip.String())
		if ip.To4() != nil && chosen == "" {
			chosen = ip.String()
		}
	}
	if chosen == "" && len(ips) > 0 {
		chosen = ips[0].String()
	}
	return chosen, all, time.Since(start), nil
}
