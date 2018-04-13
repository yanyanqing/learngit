package main

import (
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/juju/errors"
	"github.com/ngaut/log"
	//	"github.com/sirupsen/logrus"
)

/*
func init() {
	// Log as Text instead of the default ASCII formatter
	logrus.SetFormatter(&logrus.JSONFormatter{})

	// Output to stdout instead of the default stderr
	logrus.SetOutput(os.Stdout)

	// Only log the warning severity or above
	logrus.SetLevel(logrus.DebugLevel)
}
*/
func main() {
	cfg := NewConfig()
	if err := cfg.Parse(os.Args[1:]); err != nil {
		log.Fatalf("verifying flags error %s", errors.ErrorStack(err))
	}

	issServer := NewServer(cfg)
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
	log.Fatal(http.ListenAndServe(":32333", nil))
}
