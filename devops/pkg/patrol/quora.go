package patrol

import (
	"bytes"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/BurntSushi/toml"
	"github.com/PuerkitoBio/goquery"
	"github.com/juju/errors"
	"github.com/ngaut/log"
	"golang.org/x/net/context"
)

var patrolInterval = 24 * time.Hour

// QuoraScraper scrapes quora website
type QuoraScraper struct {
	cfg *Config

	ctx    context.Context
	cancel context.CancelFunc

	postChan chan *Post
	wg       sync.WaitGroup

	slackSender *SlackSender
	persistence *Persistence
}

// newQuoraScraper returns an instance of QuoraScraper
func newQuoraScraper(cfg *Config) (*QuoraScraper, error) {
	ctx, cancel := context.WithCancel(context.Background())

	persistence := newPersistence(cfg)
	slackSender := newSlackSender(cfg)

	return &QuoraScraper{
		cfg:         cfg,
		ctx:         ctx,
		cancel:      cancel,
		postChan:    make(chan *Post),
		slackSender: slackSender,
		persistence: persistence,
	}, nil
}

func (qs *QuoraScraper) doScrape(url string, query string) error {
	log.Infof("cache posts are %v\n", qs.persistence.Posts)
	doc, err := goquery.NewDocument(url + query)
	if err != nil {
		log.Fatal(err)
		return errors.Trace(err)
	}

	doc.Find(".pagedlist_item").Each(func(i int, s *goquery.Selection) {

		var articleID, link, title, author, snippet, createTime, modifyTime string
		articleID, _ = s.Attr("id")
		author = s.Find(".QuestionQueryResult .row .answer_author.light_gray .rendered_qtext").Text()
		snippet = s.Find(".QuestionQueryResult .row .search_result_snippet .rendered_qtext .qtext_para").Text()

		title = s.Find(".QuestionQueryResult .title .question_link .question_text .rendered_qtext").Text()

		if href, ok := s.Find(".QuestionQueryResult .title .question_link").Attr("href"); ok {
			link = href
		}

		if link != "" {
			link = url + link
		}
		if title != "" {
			qs.postChan <- &Post{
				URL:        url,
				Query:      query,
				ArticleID:  articleID,
				ArticlePos: i,
				Title:      title,
				Author:     author,
				Link:       link,
				Snippet:    snippet,
				CreateTime: createTime,
				ModifyTime: modifyTime,
			}
		}

	})

	return nil
}

func (qs *QuoraScraper) handlePost() error {
	qs.wg.Add(1)
	defer qs.wg.Done()

	for {
		select {
		case <-qs.ctx.Done():
			log.Infof("ctx cancel")
			return nil
		case postMsg := <-qs.postChan:
			key := postMsg.URL + ":" + postMsg.Title
			if qs.persistence.diff(key) == nil {
				qs.persistence.Posts[key] = postMsg

				err := qs.slackSender.sendMsg(postMsg)
				if err != nil {
					return errors.Trace(err)
				}

				printPost(postMsg)

				err1 := qs.persistence.save(postMsg)
				if err1 != nil {
					log.Errorf("save message error %s", err1)
					return errors.Trace(err1)
				}
			}
		}
	}
}

// Scrape implements Scraper.Scrape interface
func (qs *QuoraScraper) Scrape() error {
	var i int
	var url string
	var querys []string

	for ; i < len(qs.cfg.Page); i++ {
		if qs.cfg.Page[i].Identifier == "quora" {
			url = qs.cfg.Page[i].URL
			querys = strings.Split(strings.Trim(qs.cfg.Page[i].Query, " "), ",")
			log.Infof("querys %v", querys)
			break
		}
	}

	qs.wg.Add(len(querys))

	for i := 0; i < len(querys); i++ {
		go qs.run(url, querys[i])
	}

	qs.wg.Wait()

	return nil
}

func (qs *QuoraScraper) run(url string, query string) error {
	err := qs.persistence.load()
	if err != nil {
		return errors.Trace(err)
	}

	defer qs.wg.Done()
	go qs.handlePost()

	for {
		select {
		case <-qs.ctx.Done():
			log.Infof("ctx cancel")
			return nil
		case <-time.After(patrolInterval):
			err := qs.doScrape(url, query)
			if err != nil {
				log.Errorf("scrape website %s error %v", url, err)
				return errors.Trace(err)
			}
		}
	}
}

func printPost(post *Post) {
	var buf bytes.Buffer
	e := toml.NewEncoder(&buf)
	err := e.Encode(post)
	if err != nil {
		log.Errorf("toml encode error %v", err)
		return
	}

	fmt.Println(string(buf.Bytes()))
}
