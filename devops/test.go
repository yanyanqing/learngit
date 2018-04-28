package main

import (
//	"fmt"
	"io/ioutil"

	"github.com/andygrunwald/go-jira"
	"github.com/ngaut/log"
	//"github.com/juju/errors"
)

func main(){
	client, err := jira.NewClient(nil, "http://107.150.119.233:8080/")
	if err != nil {
		log.Errorf("createclient error %v", err)
		return 
	}

	// components := make([]*jira.Component, 1)
	// components[0] = &jira.Component{}

	 client.Authentication.SetBasicAuth("yanyanqing@pingcap.com", "abcd1234abcd")

	// fields := &jira.IssueFields{
	// 	Type: jira.IssueType{
	// 		Name: "Task", // TODO: Determine issue type
	// 	},
	// 	Project: jira.Project{
	// 		Key: "TEST",
	// 	},
	// 	Components:  components,
		
	// 	Summary:     "for test",
	// 	Description: "XXXXX",
	// }

	// issue := &jira.Issue{
	// 	Fields:fields,
	// }

	// issue, res, err := client.Issue.Create(issue)
	// if err != nil{
	// 	data, _ := ioutil.ReadAll(res.Body)
	// 	log.Errorf("error %v", string(data))
	// }

	//trans, _, err := client.Issue.GetTransitions("10183")
	//log.Infof("create issue %v status %v", trans, err)

	// issue, res, err := client.Issue.Get("10183",nil)
	// if err != nil{
	// 	data, _ := ioutil.ReadAll(res.Body)
	// 	log.Errorf("error %v", string(data))
	// }
	// log.Infof("create status %v",*issue.Fields.Status)
	// //issue.Fields.Status.Name = "Done"
	// mma := make (map[string]interface{})
	// // i := make(map[string]interface{})
	// // i["Status"] = &jira.Status{
	// // 	Name:"Done",
	// // }
	// fields.Status = &jira.Status{
	// 	Name:"Done",
	// }
	// mma["fields"] = fields
	// // mma["Fields"] = &jira.IssueFields{
	// // 	Status : &jira.Status{
	// // 		Name :"Done",
	// // 	},
	// // }
	// // req, err := client.NewRequest("POST", fmt.Sprintf("rest/api/2/issue/%s/", issue.Key), nil)
	// // if err != nil {
	// // 	log.Errorf("Delete error %v", err)
	// // }
	// res, err = client.Issue.UpdateIssue(issue.Key,mma)
	// //res, err = client.Do(req, mma)
	res, err := client.Issue.DoTransition("10183","41")
	if err != nil{
		data, _ := ioutil.ReadAll(res.Body)
		log.Errorf("error %v", string(data))
	}
	// fmt.Printf("create status %v",*issue.Fields.Status)

}