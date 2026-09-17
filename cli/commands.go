package main

import (
	"encoding/csv"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"time"
)

// ---- probe ----

func runProbe(args []string) int {
	var c proxyCfg
	var target string
	var timeout time.Duration
	fs := flag.NewFlagSet("probe", flag.ContinueOnError)
	addProxyFlags(fs, &c)
	fs.StringVar(&target, "target", "google.com:80", "CONNECT target host:port (engine uses google.com:80)")
	fs.DurationVar(&timeout, "timeout", 5*time.Second, "dial and read/write timeout (engine: 5s)")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if c.host == "" {
		fmt.Fprintln(os.Stderr, "probe needs -host (use check or dns for direct baseline)")
		return 2
	}
	tHost, tPort := splitTarget(target)
	res := ProbeSocks5(c.host, c.port, c.user, c.pass, tHost, tPort, timeout)
	p := res.Phases
	fmt.Printf("proxy:  %s:%d\n", c.host, c.port)
	fmt.Printf("target: %s\n", target)
	fmt.Printf("result: %s (%s)\n", probeDisplay(res.Outcome), res.Outcome)
	fmt.Printf("tcp=%sms handshake=%sms auth=%sms connect=%sms total=%sms\n",
		ms(p.TCP), ms(p.Handshake), ms(p.Auth), ms(p.Connect),
		ms(p.TCP+p.Handshake+p.Auth+p.Connect))
	if res.Detail != "" {
		fmt.Printf("detail: %s\n", res.Detail)
	}
	if res.Outcome != ProbeOK {
		return 1
	}
	return 0
}

func splitTarget(target string) (string, int) {
	host, port := target, 80
	if i := strings.LastIndex(target, ":"); i >= 0 {
		host = target[:i]
		fmt.Sscanf(target[i+1:], "%d", &port)
	}
	return host, port
}

// ---- check ----

func runCheck(args []string) int {
	var c proxyCfg
	var primary, mode string
	var timeout time.Duration
	fs := flag.NewFlagSet("check", flag.ContinueOnError)
	addProxyFlags(fs, &c)
	fs.StringVar(&primary, "primary", "kiloip", "primary checker: kiloip or trace (engine default: kiloip)")
	fs.StringVar(&mode, "mode", "both", "both = fallback/enrich with the other checker, single = primary only")
	fs.DurationVar(&timeout, "timeout", 8*time.Second, "checker connect/read timeout (engine: 8s)")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	first, second := CheckWith(c.host, c.port, c.user, c.pass, primary, mode == "both", timeout)
	printCheck(first)
	if mode == "both" {
		printCheck(second)
	}
	if first.Info == nil {
		return 1
	}
	return 0
}

func printCheck(r CheckResult) {
	if r.Tag == "" {
		return
	}
	via := "direct"
	if r.ViaProxy {
		via = "proxy"
	}
	if r.Info == nil {
		fmt.Printf("[%s via %s] FAIL %sms: %s\n", r.Tag, via, ms(r.Elapsed), r.Err)
		return
	}
	i := r.Info
	geo := strings.Trim(strings.Join([]string{i.Country, i.RegionName, i.City}, ", "), ", ")
	fmt.Printf("[%s via %s] %sms ip=%s country=%s",
		r.Tag, via, ms(r.Elapsed), i.IP, firstNonEmpty(i.CountryCode, geo))
	if i.ISP != "" {
		fmt.Printf(" isp=%s", i.ISP)
	}
	if i.Org != "" && i.Org != i.ISP {
		fmt.Printf(" org=%s", i.Org)
	}
	if i.Timezone != "" {
		fmt.Printf(" tz=%s", i.Timezone)
	}
	fmt.Println()
}

func firstNonEmpty(v ...string) string {
	for _, s := range v {
		if s != "" {
			return s
		}
	}
	return "-"
}

// ---- bench ----

func runBench(args []string) int {
	var c proxyCfg
	var primary, mode, csvPath string
	var repeat int
	var timeout time.Duration
	fs := flag.NewFlagSet("bench", flag.ContinueOnError)
	addProxyFlags(fs, &c)
	fs.StringVar(&primary, "primary", "kiloip", "primary checker: kiloip or trace")
	fs.StringVar(&mode, "mode", "both", "both or single")
	fs.IntVar(&repeat, "repeat", 5, "iterations")
	fs.DurationVar(&timeout, "timeout", 8*time.Second, "checker timeout (probe phases use min(timeout,5s) like the engine)")
	fs.StringVar(&csvPath, "csv", "", "write per-iteration rows to CSV file")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if repeat < 1 {
		repeat = 1
	}
	probeTimeout := timeout
	if probeTimeout > 5*time.Second {
		probeTimeout = 5 * time.Second
	}

	type row struct {
		resolve, tcp, hs, auth, conn, check time.Duration
		outcome, ip, country                string
	}
	rows := make([]row, 0, repeat)
	var totals, checks []time.Duration
	ok := 0
	for i := 0; i < repeat; i++ {
		var r row
		if c.host != "" {
			chosen, _, dnsMs, err := ResolveHost(c.host, probeTimeout)
			r.resolve = dnsMs
			if err != nil {
				fmt.Printf("iter %d: dns FAIL: %v\n", i+1, err)
				continue
			}
			_ = chosen
		}
		pr := ProbeSocks5(c.host, c.port, c.user, c.pass, "google.com", 80, probeTimeout)
		r.tcp, r.hs, r.auth, r.conn = pr.Phases.TCP, pr.Phases.Handshake, pr.Phases.Auth, pr.Phases.Connect
		r.outcome = pr.Outcome
		total := r.resolve + r.tcp + r.hs + r.auth + r.conn
		if pr.Outcome == ProbeOK {
			first, _ := CheckWith(c.host, c.port, c.user, c.pass, primary, false, timeout)
			r.check = first.Elapsed
			if first.Info != nil {
				r.ip, r.country = first.Info.IP, first.Info.CountryCode
			}
		}
		rows = append(rows, r)
		totals = append(totals, total)
		checks = append(checks, r.check)
		if pr.Outcome == ProbeOK {
			ok++
		}
		fmt.Printf("iter %d: probe=%s resolve=%sms tcp=%sms hs=%sms auth=%sms conn=%sms check=%sms ip=%s %s\n",
			i+1, pr.Outcome, ms(r.resolve), ms(r.tcp), ms(r.hs), ms(r.auth), ms(r.conn),
			ms(r.check), firstNonEmpty(r.ip, "-"), firstNonEmpty(r.country, ""))
	}

	ts, cs := summarize(totals), summarize(checks)
	fmt.Printf("probe ok: %d/%d\n", ok, repeat)
	fmt.Printf("connect-phase total: min=%sms med=%sms avg=%sms max=%sms\n",
		ms(ts.min), ms(ts.med), ms(ts.avg), ms(ts.max))
	fmt.Printf("checker (%s): min=%sms med=%sms avg=%sms max=%sms\n",
		primary, ms(cs.min), ms(cs.med), ms(cs.avg), ms(cs.max))

	if csvPath != "" {
		f, err := os.Create(csvPath)
		if err != nil {
			fmt.Fprintln(os.Stderr, "csv:", err)
			return 1
		}
		defer f.Close()
		w := csv.NewWriter(f)
		_ = w.Write([]string{"iter", "resolve_ms", "tcp_ms", "handshake_ms", "auth_ms", "connect_ms", "total_ms", "outcome", "check_ms", "exit_ip", "country"})
		for i, r := range rows {
			_ = w.Write([]string{
				fmt.Sprint(i + 1), ms(r.resolve), ms(r.tcp), ms(r.hs), ms(r.auth), ms(r.conn),
				ms(r.resolve + r.tcp + r.hs + r.auth + r.conn),
				r.outcome, ms(r.check), r.ip, r.country,
			})
		}
		w.Flush()
		if err := w.Error(); err != nil {
			fmt.Fprintln(os.Stderr, "csv:", err)
			return 1
		}
		fmt.Println("csv:", csvPath)
	}
	if ok == 0 {
		return 1
	}
	return 0
}

// ---- sweep ----

func runSweep(args []string) int {
	var file, primary, mode string
	var concurrency int
	var timeout time.Duration
	fs := flag.NewFlagSet("sweep", flag.ContinueOnError)
	fs.StringVar(&file, "file", "", "proxy list file (host:port[:user:pass] per line, # comments)")
	fs.StringVar(&primary, "primary", "kiloip", "primary checker: kiloip or trace")
	fs.StringVar(&mode, "mode", "single", "single or both (both doubles requests; default single for bulk)")
	fs.IntVar(&concurrency, "concurrency", 4, "parallel proxies")
	fs.DurationVar(&timeout, "timeout", 8*time.Second, "checker timeout")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if file == "" {
		fmt.Fprintln(os.Stderr, "sweep needs -file")
		return 2
	}
	data, err := os.ReadFile(file)
	if err != nil {
		fmt.Fprintln(os.Stderr, "sweep:", err)
		return 1
	}
	var list []proxyCfg
	for _, line := range strings.Split(string(data), "\n") {
		c, err := parseSweepLine(line)
		if err != nil {
			continue
		}
		list = append(list, c)
	}
	if len(list) == 0 {
		fmt.Fprintln(os.Stderr, "sweep: no proxies in", file)
		return 1
	}
	if concurrency < 1 {
		concurrency = 1
	}
	probeTimeout := timeout
	if probeTimeout > 5*time.Second {
		probeTimeout = 5 * time.Second
	}

	type result struct {
		c       proxyCfg
		outcome string
		totalMs string
		ip      string
		country string
	}
	results := make([]result, len(list))
	sem := make(chan struct{}, concurrency)
	var wg sync.WaitGroup
	for i, c := range list {
		wg.Add(1)
		go func(i int, c proxyCfg) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			pr := ProbeSocks5(c.host, c.port, c.user, c.pass, "google.com", 80, probeTimeout)
			r := result{c: c, outcome: string(pr.Outcome), ip: "-", country: "-"}
			p := pr.Phases
			r.totalMs = ms(p.TCP + p.Handshake + p.Auth + p.Connect)
			if pr.Outcome == ProbeOK {
				first, _ := CheckWith(c.host, c.port, c.user, c.pass, primary, mode == "both", timeout)
				r.totalMs += "+" + ms(first.Elapsed)
				if first.Info != nil {
					r.ip = first.Info.IP
					r.country = firstNonEmpty(first.Info.CountryCode, "-")
				} else {
					r.ip = "check-fail"
				}
			}
			results[i] = r
		}(i, c)
	}
	wg.Wait()

	fmt.Printf("%-28s %-14s %-14s %-16s %s\n", "PROXY", "PROBE", "PROBE+CHECK MS", "EXIT IP", "CC")
	counts := map[string]int{}
	for _, r := range results {
		counts[r.outcome]++
		fmt.Printf("%-28s %-14s %-14s %-16s %s\n",
			fmt.Sprintf("%s:%d", r.c.host, r.c.port), r.outcome, r.totalMs, r.ip, r.country)
	}
	fmt.Printf("total=%d ok=%d\n", len(results), counts[ProbeOK])
	for outcome, n := range counts {
		if outcome != ProbeOK {
			fmt.Printf("  %s=%d", outcome, n)
		}
	}
	fmt.Println()
	if counts[ProbeOK] == 0 {
		return 1
	}
	return 0
}

// ---- speed ----

func runSpeed(args []string) int {
	var c proxyCfg
	var url string
	var timeout time.Duration
	var direct bool
	fs := flag.NewFlagSet("speed", flag.ContinueOnError)
	addProxyFlags(fs, &c)
	fs.StringVar(&url, "url", "https://speed.cloudflare.com/__down?bytes=5000000", "download URL (5 MB default)")
	fs.BoolVar(&direct, "direct", false, "bypass proxy: baseline download without SOCKS")
	fs.DurationVar(&timeout, "timeout", 60*time.Second, "overall download timeout")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	host := c.host
	if direct {
		host = ""
	} else if host == "" {
		fmt.Fprintln(os.Stderr, "speed needs -host or -direct")
		return 2
	}
	client := checkerClient(host, c.port, c.user, c.pass, 8*time.Second)
	client.Timeout = timeout
	start := time.Now()
	resp, err := client.Get(url)
	if err != nil {
		fmt.Println("FAIL:", err)
		return 1
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		fmt.Println("FAIL: http", resp.StatusCode)
		return 1
	}
	n, err := io.Copy(io.Discard, resp.Body)
	elapsed := time.Since(start)
	if err != nil {
		fmt.Println("FAIL:", err)
		return 1
	}
	secs := elapsed.Seconds()
	mbps := float64(n) * 8 / secs / 1e6
	via := "proxy"
	if direct {
		via = "direct"
	}
	fmt.Printf("via=%s bytes=%d time=%sms speed=%.2f Mbps\n", via, n, ms(elapsed), mbps)
	return 0
}

// ---- dns ----

func runDNS(args []string) int {
	var host string
	var repeat int
	var timeout time.Duration
	fs := flag.NewFlagSet("dns", flag.ContinueOnError)
	fs.StringVar(&host, "host", "", "hostname to resolve")
	fs.IntVar(&repeat, "repeat", 1, "repeat N times for min/med/max (accelerator DNS analysis)")
	fs.DurationVar(&timeout, "timeout", 5*time.Second, "lookup timeout")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if host == "" {
		fmt.Fprintln(os.Stderr, "dns needs -host")
		return 2
	}
	if repeat < 1 {
		repeat = 1
	}
	var durs []time.Duration
	var chosen string
	var all []string
	for i := 0; i < repeat; i++ {
		c, a, d, err := ResolveHost(host, timeout)
		if err != nil {
			fmt.Printf("iter %d: FAIL: %v\n", i+1, err)
			continue
		}
		chosen, all = c, a
		durs = append(durs, d)
		if repeat > 1 {
			fmt.Printf("iter %d: %sms chosen=%s\n", i+1, ms(d), c)
		}
	}
	if len(durs) == 0 {
		return 1
	}
	s := summarize(durs)
	fmt.Printf("host=%s chosen=%s all=[%s]\n", host, chosen, strings.Join(all, " "))
	fmt.Printf("lookups=%d min=%sms med=%sms avg=%sms max=%sms\n",
		s.n, ms(s.min), ms(s.med), ms(s.avg), ms(s.max))
	return 0
}
