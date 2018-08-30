package org.tronscan.api

import play.api.mvc.InjectedController

trait BaseApi extends InjectedController {

  val navParams = List(
    "start",
    "limit",
    "sort",
    "count",
  )

  /**
    * Strip query parameters which are related to paging and navigation of the results
    */
  def stripNav(params: Map[String, String], sortParams: List[String] = List.empty) = {
    val navs = navParams ++ sortParams
    params.filterKeys(x => !navs.contains(x))
  }

}
