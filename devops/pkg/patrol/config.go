package patrol

import (
	"flag"
	"os"

	"github.com/BurntSushi/toml"
	"github.com/juju/errors"
)

// WebPage holds a web page configuration
type WebPage struct {
	URL        string `toml:"url" json:"url"`
	Query      string `toml:"query" json:"query"`
	Identifier string `toml:"identifier" json:"identifier"`
}

// Config holds slack configuration
type Config struct {
	*flag.FlagSet

	BotToken string `toml:"botToken" json:"botToken"`

	VerificationToken string `toml:"verificationToken" json:"verificationToken"`

	ChannelID string `toml:"channelID" json:"channelID"`

	Page []*WebPage `toml:"url-query-identifier" json:"url-query-identifier"`

	configFile string
}

// NewConfig return an instance of configuration
func NewConfig() *Config {
	cfg := &Config{}
	cfg.FlagSet = flag.NewFlagSet("patrol", flag.ContinueOnError)
	fs := cfg.FlagSet

	fs.StringVar(&cfg.BotToken, "bot-token", "", "the bot token is used to access slack")
	fs.StringVar(&cfg.configFile, "config", "", "path to config file")
	fs.StringVar(&cfg.VerificationToken, "varification-token", "", "the verification token is used to varify")
	fs.StringVar(&cfg.ChannelID, "channel-id", "", "the channelID which slack client send message to")

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

	if len(cfg.FlagSet.Args()) != 0 {
		return errors.Errorf("'%s' is an invalid flag", cfg.FlagSet.Arg(0))
	}

	if cfg.BotToken == "" {
		return errors.New("bot user token should be given")
	}

	if cfg.ChannelID == "" {
		return errors.New("bot channelID is invalid")
	}

	return nil
}

func (cfg *Config) configFromFile(configFile string) error {
	_, err := toml.DecodeFile(configFile, cfg)
	return errors.Trace(err)
}
