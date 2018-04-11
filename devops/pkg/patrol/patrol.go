package main

import (
	"sync"

	"github.com/juju/errors"
)

// Patrol holds scrape web function
type Patrol struct {
	wg sync.WaitGroup

	cfg *Config

	scrapers []Scraper
}

// NewPatrol returns a patrol instance by giving argument
func NewPatrol(cfg *Config) *Patrol {
	return &Patrol{cfg: cfg}
}

func (patrol *Patrol) init() error {
	for i := 0; i < len(patrol.cfg.Page); i++ {
		scraper, err := NewScraper(patrol.cfg.Page[i].Identifier, patrol.cfg)
		if err != nil {
			return errors.Trace(err)
		}
		patrol.scrapers = append(patrol.scrapers, scraper)
	}

	return nil
}

func (patrol *Patrol) run() {
	patrol.wg.Add(len(patrol.scrapers))

	for i := 0; i < len(patrol.scrapers); i++ {
		go patrol.scrapers[i].Scrape()
	}

	patrol.wg.Wait()
}
