package org.tronscan.db

import org.joda.time.DateTime
import play.api.Logger
import play.api.mvc.{AnyContent, Request}
import slick.jdbc.{GetResult, JdbcBackend}
import org.tronscan.db.PgProfile.api._
import play.api.db.slick.DatabaseConfigProvider
import slick.dbio.{Effect, NoStream}
import slick.sql.FixedSqlAction

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Failure


trait RepositoryMessage {
  val message: String
}

object QueryUtils {
  implicit val resultAsStringMap = GetResult[Map[String,String]] ( prs =>
    (1 to prs.numColumns).map(_ =>
      prs.rs.getMetaData.getColumnName(prs.currentPos + 1) -> prs.nextString
    ).toMap
  )

  implicit val resultAsAnyMap = GetResult[Map[String,Any]] ( prs =>
    (1 to prs.numColumns).map(_ =>
      prs.rs.getMetaData.getColumnName(prs.currentPos + 1) -> prs.rs.getObject(prs.currentPos + 1)
    ).toMap
  )
}

case class EntityNotFound(message: String) extends RepositoryMessage

trait Repository {

  val dbConfig: DatabaseConfigProvider

  def db: JdbcBackend#DatabaseDef = dbConfig.get[PgProfile].db

  protected def run[R](a : slick.dbio.DBIOAction[R, slick.dbio.NoStream, scala.Nothing]) : Future[R] = {
    val statement = db.run(a)
    statement.onComplete {
      case Failure(exc) =>
        Logger.error("Query Exception", exc)
      case _ =>
    }
    statement
  }
}

trait Entity {
  def id: Option[Int]
}

case class EntityModel(id: Option[Int]) extends Entity

/**
  * Base for Entity Table
  *
  * @param tableName name of the table
  * @tparam T Entity type
  */
abstract class EntityTable[T <: Entity](tag: Tag, tableName: String) extends Table[T](tag, tableName) {

  def id: Rep[Int] = column[Int]("id", O.PrimaryKey, O.AutoInc)
}

/**
  * Slick Table Repository
  *
  * @tparam T Table type
  * @tparam E Entity Type
  */
trait TableRepository[T <: Table[E], E <: Any] extends Repository {

  type QueryType = Query[T, T#TableElementType, Seq]
  type FQ = QueryType => QueryType

  def table: TableQuery[T]

  def query: QueryType = table.q

  def readAsync[TR, TG](q:  Query[TR, TG, Seq]): Future[Seq[TG]] = run {
    q.result
  }

  def readAny[TR, TG, R](q:  QueryType => slick.dbio.DBIOAction[R, slick.dbio.NoStream, scala.Nothing]): Future[R] = run {
    q(table)
  }

  def readQuery[TR, TG](func: QueryType => Query[TR, TG, Seq]) = run {
    func(table).result
  }

  def readSingle(func: QueryType => QueryType): Future[Option[E]] = run {
    func(table).result.headOption
  }

  def del(func: QueryType => QueryType): Future[Int] = run {
    func(table).delete
  }

  def readTotal[TR, TG](query: Query[TR, TG, Seq]) = run {
    query.length.result
  }

  /**
    * @deprecated
    */
  def runFilterWithRequest(query: FQ)(e: (FQ, (String, String)) => FQ)(implicit request: Request[AnyContent]): Future[Seq[E]] = {
    val params = request.queryString.map(x => (x._1, x._2.mkString))
    runQuery(params.foldLeft(query)(e))
  }

  def filterSomeToken(fun: QueryType => QueryType)(implicit request: Request[AnyContent]): QueryType => QueryType = { (query: QueryType) =>
    fun(query)
  }

  def filterRequest(e: (QueryType, (String, String)) => QueryType)(implicit request: Request[AnyContent]): QueryType => QueryType = { (query: QueryType) =>
    val params = request.queryString.map(x => (x._1, x._2.mkString))
    params.foldLeft(query)(e)
  }

  def readRows(query: QueryType => QueryType) = run {
    query(table).result
  }

  def readTotals[TR, TG](func: QueryType => Query[TR, TG, Seq]) = run {
    func(table).length.result
  }


  def sortWithRequest(sortParam: String = "sort")(sorter: PartialFunction[(T, String), Rep[_ <: Any]])
                     (implicit request: Request[AnyContent]): QueryType => QueryType = { (query: QueryType) =>
    (for {
      json <- request.getQueryString(sortParam)
    } yield {
      json.split(",").foldLeft(query) {
        case (q, obj) =>
          // descending order is prepended with a '-' character and has to be stripped of the property
          val isDescendingOrder = obj.startsWith("-")
          val property = if (isDescendingOrder) obj.substring(1) else obj
          val direction = if (isDescendingOrder) "desc" else "asc"
          q.sortBy { x =>
            val column = sorter(x, property)
            (column, direction) match {
              case (col: Rep[Int], "asc") => col.asc
              case (col: Rep[Int], "desc") => col.desc
              case (col: Rep[String], "asc") => col.asc
              case (col: Rep[String], "desc") => col.desc
              case (col: Rep[DateTime], "asc") => col.asc
              case (col: Rep[DateTime], "desc") => col.desc
              case (col: Rep[Boolean], "asc") => col.asc
              case (col: Rep[Boolean], "desc") => col.desc
              case (col: Rep[Long], "asc") => col.asc
              case (col: Rep[Long], "desc") => col.desc
            }
          }
      }
    }).getOrElse(query)
  }

  def limitWithRequest[TR, TG](
    startParam: String = "start",
    limitParam: String = "limit",
    defaultLimit: Int = 100)(implicit request: Request[AnyContent]): Query[TG, TR, Seq] => Query[TG, TR, Seq] = {
      query: Query[TG, TR, Seq] =>

      val q1 = request.getQueryString(startParam).map(start => query.drop(start.toInt)).getOrElse(query)
      request.getQueryString(limitParam).map {
        case start if start.toInt < 100 =>
          q1.take(start.toInt)
        case _ =>
          q1.take(defaultLimit)
      }.getOrElse(q1.take(defaultLimit))
  }

  def limitWithRequestRes[TR, TG](
    startParam: String = "start",
    limitParam: String = "limit",
    defaultLimit: Int = 100)(implicit request: Request[AnyContent]): (Int, Int, QueryType => QueryType) = {

    val startValue = request.getQueryString(startParam).map(_.toInt).getOrElse(0)
    val limitValue = request.getQueryString(limitParam).map {
      case start if start.toInt < 100 =>
        start.toInt
      case _ =>
        defaultLimit
    }.getOrElse(defaultLimit)

    val filter: QueryType => QueryType = query => query.drop(startValue).take(limitValue)

    (startValue, limitValue, filter)
  }

  def runQuery(query: FQ): Future[Seq[E]] = run(query(table).result)

  def insertAsync(entity: E): Future[Int] = run {
    table += entity
  }

  def buildInsert(entity: E) = {
    table += entity
  }

  def buildInsertOrUpdate(entity: E) = {
    table.insertOrUpdate(entity)
  }

  def insertAsync(entity: Seq[E]): Future[Unit] = run {
    DBIO.seq(entity.map(x => table += x): _*)
  }

  def createAsync(entity: E): Future[E] = run {
    table returning table += entity
  }

  def executeQueries(queries: Seq[DBIOAction[_, NoStream, Effect.Write]]) = run {
    DBIO.seq(queries: _*).transactionally.withPinnedSession
  }
}

trait TableEntityRepository[T <: EntityTable[E], E <: Entity] extends TableRepository[T, E] {

  val entityNotFound = "Entity not found"

  def findAllAsync(): Future[Seq[E]] = run {
    table.result
  }

  def findByIdAsync(id: Int): Future[Option[E]] = run {
    table.filter(x => x.id === id).result.headOption
  }

  def updateAsync(id: Int, entity: E): Future[Int] = run {
    table.filter(_.id === id).update(entity)
  }

  def updateAsync(entity: E): Future[Int] = run {
    table.filter(_.id === entity.id).update(entity)
  }

  def deleteAsync(entity: E) = run {
    table.filter(_.id === entity.id).delete
  }

  def saveAsync(entity: E): Unit = {
    if (entity.id.isDefined) updateAsync(entity) else insertAsync(entity)
  }

}
