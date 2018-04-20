package main

import (
	"os"
	"os/signal"
	"syscall"

	"github.com/juju/errors"
	"github.com/ngaut/log"
	"github.com/pingcap/SRE/devops/pkg/patrol"
)

func main() {
	cfg := patrol.NewConfig()
	if err := cfg.Parse(os.Args[1:]); err != nil {
		log.Fatalf("verifying flags error %s", errors.ErrorStack(err))
	}

	patrol := patrol.NewPatrol(cfg)
	err := patrol.Init()
	if err != nil {
		log.Errorf("init patrol error %v", err)
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

	patrol.Run()
}
