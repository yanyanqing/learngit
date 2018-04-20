package patrol

import (
	"github.com/juju/errors"
)

// Scraper is the Partorl Scraper
type Scraper interface {
	Scrape() error
}

// NewScraper returns an instance of Scraper
func NewScraper(name string, cfg *Config) (Scraper, error) {
	switch name {
	case "quora":
		return newQuoraScraper(cfg)
	default:
		return nil, errors.Errorf("unsupport scraper type %s", name)
	}
}
