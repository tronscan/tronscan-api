package org.tronscan.service

import javax.inject.Inject
import play.api.libs.ws.WSClient

import scala.concurrent.{ExecutionContext, Future}

case class SRPages(
  intro: String,
  communityPlan: String,
  team: String,
  budgetExpenses: String,
  serverConfiguration: String)

class SRService @Inject() (wSClient: WSClient) {

  def loadPage(url: String)(implicit executionContext: ExecutionContext): Future[String] = {
    wSClient.url(url).get().map(_.body)
  }

  def getPages(repository: String, language: String = "en")(implicit executionContext: ExecutionContext) = {

    var url = "https://raw.githubusercontent.com/${repository}/master"

    if (language != "en") {
      url += s"/pages/$language"
    }

    val introF = loadPage(url + "/INTRO.md")
    val communityPlanF = loadPage(url + "/COMMUNITY_PLAN.md")
    val teamF = loadPage(url + "/TEAM.md")
    val budgetExpensesF = loadPage(url + "/BUDGET_EXPENSES.md")
    val serverConfigurationF = loadPage(url + "/SERVER_CONFIGURATION.md")

    for {
     intro <- introF
     communityPlan <- communityPlanF
     team <- teamF
     budgetExpenses <- budgetExpensesF
     serverConfiguration <- serverConfigurationF
    } yield {
      SRPages(
        intro = intro,
        communityPlan = communityPlan,
        team = team,
        budgetExpenses = budgetExpenses,
        serverConfiguration = serverConfiguration)
    }
  }

}
