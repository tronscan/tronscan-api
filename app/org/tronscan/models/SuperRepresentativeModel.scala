package org.tronscan.models

import com.google.inject.{Inject, Singleton}
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.{JsValue, Json}
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository

import scala.concurrent.{ExecutionContext, Future}

case class SuperRepresentativeModel(
  address: String,
  githubLink: Option[String] = None)

class SuperRepresentativeModelTable(tag: Tag) extends Table[SuperRepresentativeModel](tag, "sr_account") {
  def address = column[String]("address", O.PrimaryKey)
  def githubLink = column[String]("github_link")
  def * = (address, githubLink.?) <> (SuperRepresentativeModel.tupled, SuperRepresentativeModel.unapply)
}

@Singleton()
class SuperRepresentativeModelRepository @Inject() (
  val dbConfig: DatabaseConfigProvider) extends TableRepository[SuperRepresentativeModelTable, SuperRepresentativeModel] {

  lazy val table = TableQuery[SuperRepresentativeModelTable]

  def findAll = run {
    table.result
  }

  def findByAddress(address: String) = run {
    table.filter(_.address === address).result.headOption
  }

  def updateAsync(entity: SuperRepresentativeModel): Future[Int] = run {
    table.filter(_.address === entity.address).update(entity)
  }

  def saveAsync(entity: SuperRepresentativeModel)(implicit executionContext: ExecutionContext) ={

    findByAddress(entity.address).flatMap {
      case Some(existing) =>
        updateAsync(entity)
      case _ =>
        insertAsync(entity)
    }
  }

}