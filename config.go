package main

import (
	"flag"
	"fmt"
	"os"
	"time"

	//	"github.com/sirupsen/logrus"
	"github.com/BurntSushi/toml"
	"github.com/juju/errors"
)

type GHConfig struct {
	Username string `toml:"github-username" json:"github-username"`
	Password string `toml:"github-password" json:"github-password"`
}

type JIRAConfig struct {
	Username   string   `toml:"jira-username" json:"jira-username"`
	Password   string   `toml:"jira-password" json:"jira-password"`
	Endpoint   string   `toml:"jira-endpoint" json:"jira-endpoint"`
	Project    string   `toml:"jira-project" json:"jira-project"`
	Components []string `toml:"jira-components" json:"jira-components"`
}

// fieldKey is an enum-like type to represent the customfield ID keys
type fieldKey int

const (
	GitHubID       fieldKey = iota
	GitHubNumber   fieldKey = iota
	GitHubLabels   fieldKey = iota
	GitHubStatus   fieldKey = iota
	GitHubReporter fieldKey = iota
	LastISUpdate   fieldKey = iota
)

// fields represents the custom field IDs of the JIRA custom fields we care about
type fields struct {
	githubID       string
	githubNumber   string
	githubLabels   string
	githubReporter string
	githubStatus   string
	lastUpdate     string
}

type Config struct {
	*flag.FlagSet

	WebhookSecret string     `toml:"webhook-secret" json:"webhook-secret"`
	GConfig       GHConfig   `toml:"github-config" json:"github-config"`
	JConfig       JIRAConfig `toml:"jira-config" json:"jira-config"`
	// log is a logger set up with the configured log level, app name, etc.
	//log logrus.Entry

	// basicAuth represents whether we're using HTTP Basic authentication or OAuth.
	basicAuth bool

	// fieldIDs is the list of custom fields we pulled from the `fields` JIRA endpoint.
	fieldIDs fields

	// since is the parsed value of the `since` configuration parameter, which is the earliest that
	// a GitHub issue can have been updated to be retrieved.
	since time.Time

	configFile string
}

func NewConfig() *Config {
	cfg := &Config{}
	cfg.FlagSet = flag.NewFlagSet("hook", flag.ContinueOnError)
	fs := cfg.FlagSet

	fs.StringVar(&cfg.configFile, "config", "", "path to config file")
	fs.StringVar(&cfg.GConfig.Username, "github-username", "", "")
	fs.StringVar(&cfg.GConfig.Password, "github-password", "", "")
	fs.StringVar(&cfg.WebhookSecret, "webhook-secret", "", "for validating webhook message")
	fs.StringVar(&cfg.JConfig.Username, "jira-username", "", "")
	fs.StringVar(&cfg.JConfig.Password, "jira-password", "", "")
	fs.StringVar(&cfg.JConfig.Endpoint, "jira-endpoint", "", "jira endpoint for dispatch issue")
	fs.StringVar(&cfg.JConfig.Project, "jira-project", "", "jira project for syncing issue")
	return cfg
}

// Parse parses all config from command-line flags
func (cfg *Config) Parse(args []string) error {
	perr := cfg.FlagSet.Parse(args)
	switch perr {
	case nil:
	case flag.ErrHelp:
		os.Exit(0)
	default:
		os.Exit(2)
	}

	// Load config file if specified.
	if cfg.configFile != "" {
		err := cfg.configFromFile(cfg.configFile)
		if err != nil {
			return errors.Trace(err)
		}
	}

	// Parse again to replace with command line options.
	err := cfg.FlagSet.Parse(args)
	if err != nil {
		return errors.Trace(err)
	}

	return errors.Trace(validate(cfg))
}

func validate(cfg *Config) error {
	if len(cfg.FlagSet.Args()) != 0 {
		return errors.Errorf("'%s' is an invalid flag", cfg.FlagSet.Arg(0))
	}

	if cfg.WebhookSecret == "" {
		return errors.New("github webhookSecre should be given")
	}

	if cfg.JConfig.Endpoint == "" {
		return errors.New("jira endpoint should be given")
	}

	return nil
}

// getSinceParam returns the `since` configuration parameter, parsed as a time.Time.
func (c *Config) getSinceParam() time.Time {
	return c.since
}

// getLogger returns the configured application logger.
//func (c *Config) getLogger() logrus.Entry {
//	return c.log
//}

// GetFieldID returns the customfield ID of a JIRA custom field.
func (c *Config) getFieldID(key fieldKey) string {
	switch key {
	case GitHubID:
		return c.fieldIDs.githubID
	case GitHubNumber:
		return c.fieldIDs.githubNumber
	case GitHubLabels:
		return c.fieldIDs.githubLabels
	case GitHubReporter:
		return c.fieldIDs.githubReporter
	case GitHubStatus:
		return c.fieldIDs.githubStatus
	case LastISUpdate:
		return c.fieldIDs.lastUpdate
	default:
		return ""
	}
}

// getFieldKey returns customfield_XXXXX, where XXXXX is the custom field ID (see GetFieldID).
func (c *Config) getFieldKey(key fieldKey) string {
	return fmt.Sprintf("customfield_%s", c.getFieldID(key))
}

// GetProject returns the JIRA project the user has configured.
func (c *Config) getProject() string {
	return c.JConfig.Project
}

func (c *Config) getComponents() []string {
	return c.JConfig.Components
}
func (cfg *Config) configFromFile(configFile string) error {
	_, err := toml.DecodeFile(configFile, cfg)
	return errors.Trace(err)
}
