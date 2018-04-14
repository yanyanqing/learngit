package main

import (
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/pingcap/SRE/devops/pkg/jiraey"
	"github.com/juju/errors"
	"github.com/ngaut/log"
)

func main() {
	cfg := jiraey.NewConfig()
	if err := cfg.Parse(os.Args[1:]); err != nil {
		log.Fatalf("verifying flags error %s", errors.ErrorStack(err))
	}
	log.Infof("cfg %v", cfg.Port)

	issServer := jiraey.NewServer(cfg)
	err := issServer.init()
	if err != nil {
		log.Errorf("init issServer error %v", err)
		os.Exit(0)
	}

	sc := make(chan os.Signal, 1)
	signal.Notify(sc,
		syscall.SIGHUP,
		syscall.SIGINT,
		syscall.SIGTERM,
		syscall.SIGQUIT)

	go func() {
		sig := <-sc
		log.Infof("got signal [%d] to exit.", sig)
		os.Exit(0)
	}()

	go issServer.intialSync()

	http.Handle("/", issServer)
	log.Fatal(http.ListenAndServe(":"+issServer.cfg.Port, nil))
}
