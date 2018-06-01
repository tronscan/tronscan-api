package org.tronscan.api

import play.api.mvc.InjectedController

trait BaseApi extends InjectedController {

  val navParams = List(
    "start",
    "limit",
    "sort",
    "count",
  )

  def stripNav(params: Map[String, String], sortParams: List[String] = List.empty) = {
    val navs = navParams ++ sortParams
    params.filterKeys(x => !navs.contains(x))
  }

}
