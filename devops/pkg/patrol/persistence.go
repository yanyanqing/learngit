package patrol

import (
	"bytes"
	"os"

	"github.com/BurntSushi/toml"
	"github.com/juju/errors"
	"github.com/ngaut/log"
	"github.com/siddontang/go/ioutil2"
)

var persistName = "persist.txt"

// Post defines the message which is sended to slack or other systems
type Post struct {
	URL        string `toml:"url" json:"url"`
	Query      string `toml:"query" json:"query"`
	ArticleID  string `toml:"articleID" json:"articleID"`
	ArticlePos int    `toml:"articlePos" json:"articlePos"`
	Title      string `toml:"title" json:"title"`
	Link       string `toml:"link" json:"link"`
	Author     string `toml:"author" json:"author"`
	Snippet    string `toml:"snippet" json:"snippet"`
	CreateTime string `toml:"-" json:"createTime"`
	ModifyTime string `toml:"-" json:"modifyTime"`
}

// Persistence holds Persistence configuration
type Persistence struct {
	name  string
	Posts map[string]*Post `toml:"posts" json:"posts"`
}

// newPersistence returns an instance of Persistence
func newPersistence(cfg *Config) *Persistence {
	return &Persistence{
		name:  persistName,
		Posts: make(map[string]*Post),
	}
}

func (fp *Persistence) diff(key string) *Post {
	return fp.Posts[key]
}

func (fp *Persistence) load() error {
	file, err := os.Open(fp.name)
	if err != nil && !os.IsNotExist(errors.Cause(err)) {
		log.Errorf("Open file error %s", err)
		return errors.Trace(err)
	}

	if os.IsNotExist(errors.Cause(err)) {
		return nil
	}

	defer file.Close()

	_, err = toml.DecodeReader(file, fp)
	return errors.Trace(err)
}

func (fp *Persistence) save(postMsg *Post) error {
	var buf bytes.Buffer
	e := toml.NewEncoder(&buf)
	err := e.Encode(fp)
	if err != nil {
		log.Errorf("save message info to file err %v", errors.ErrorStack(err))
		return errors.Trace(err)
	}

	err = ioutil2.WriteFileAtomic(fp.name, buf.Bytes(), 0644)
	return errors.Trace(err)
}
