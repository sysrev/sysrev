package co.insilica.sysrev
package api

import co.insilica.auth.Types.UserId
import co.insilica.auth.{User, ErrorResult, AuthStack, AuthServlet}
import co.insilica.apistack.{ApiStack, Result, ResultWrapSupport}
import co.insilica.sysrev.data.ReviewTag
import co.insilica.sysrev.relationalImporter.Types.{ArticleId, WithArticleId, WithCriteriaId}
import co.insilica.sysrev.relationalImporter._

import org.scalatra._
import doobie.imports._

import scala.concurrent.Future
import scalaz._
import Scalaz._
import scalaz.concurrent.Task

import co.insilica.dataProvider.TaskFutureOps._

case class ArticleIds(articleIds: List[ArticleId])
case class CurrentUser(id: UserId, user: User)

case class LabelingTaskItem(article_id: Int, score: Double, article: ArticleWithoutKeywords)

class SysrevAuthServlet extends AuthServlet{
  override protected implicit lazy val transactor: Transactor[Task] = Implicits.transactor
}


/**
  * Add-ons for ResultWrapSupport to handle Options of tasks. Common situation with unauthenticated users for example.
  *
  */
trait RouteHelpers{ this : ApiStack with ResultWrapSupport with FutureSupport =>
  /**
    * Error task to be run, to return message indicating failure.  Override to send custom error.
    *
    * @return an immediate task wrapping an error result.
    */
  def errorTask : Task[ErrorResult[Any]] = Task.now(ErrorResult("Bad request"))

  def handleOT(action: Option[Task[Any]]): Future[Any] = action.map(_.map(Result apply _)).getOrElse(errorTask).runFuture

  def getOT(route: RouteTransformer*)(action: => Option[Task[Any]]): Route = get(route: _*)(handleOT(action))

  def postOT(route: RouteTransformer*)(action: => Option[Task[Any]]): Route = post(route: _*)(handleOT(action))
}

/**
  * Remember: Type aliases lead to infinite loops and hanging behavior in json4s.
  */
class SysrevServlet extends AuthStack with FutureSupport with ResultWrapSupport with RouteHelpers {
  val tx = Implicits.transactor
  override protected implicit lazy val transactor: Transactor[Task] = tx

  type WithUserId[T] = co.insilica.auth.Types.WithId[T]
  val WithUserId = co.insilica.auth.WithAnyId

  def getRankedPage(p: Int = 0) : Task[Map[ArticleId, WithScore[ArticleWithoutKeywords]]] =
    Queries.rankedArticlesPage(p).transact(tx).map{ xs =>
      xs.map(WithArticleId.unapply(_).get).toMap
    }

  getT("/ranking/:page")(params("page").parseInt.toOption.map(getRankedPage).getOrElse(getRankedPage(0)))

  getT("/ranking")(getRankedPage(0))

  getT("/criteria")(Queries.allCriteria().transact(tx).map(_.map(WithCriteriaId.unapply(_).get).toMap))

  getT("/search/:text")(Queries.textSearch(params("text")).transact(tx))

  /**
    * Returns a map articleid -> List[criteriaid, boolean]
    */
  getT("/allcriteria"){
    for {
      criteria <- Queries.allCriteriaResponses.transact(tx)
      articles <- Queries.articlesById(criteria.keys.toList).transact(tx)
    } yield {
      Map(
        "criteria" -> criteria,
        "articles" -> articles.map(WithArticleId.unapply(_).get).toMap
      )
    }
  }

  get("/user"){
    if(isAuthenticated) Result(CurrentUser(user.id, user.t.t))
    else ErrorResult("Not authenticated")
  }

  // Expects a [[ReviewTag]] as json, saves or updates the tag, and sends back the id of the tag.
  postOT("/tag"){
    for{
      u <- userOption
      tag <- parsedBody.extractOpt[ReviewTag]
    } yield data.Queries.tagArticle(u.id, tag).transact(tx)
  }

  postOT("/tags"){
    for{
      u <- userOption
      tags <- parsedBody.extractOpt[List[ReviewTag]]
    } yield data.Queries.updateTagsForArticle(u.id, tags).transact(tx)
  }

  getOT("/users"){
    userOption map { _ =>
      data.Queries.usersSummaryData.transact(tx)
    }
  }

  getOT("/label-task/:num"){
    val greaterThanScore : Double = params.get("greaterThanScore").flatMap(_.parseDouble.toOption).getOrElse(0.0)
    for {
      _ <- userOption
      num <- params("num").parseInt.toOption.getOrElse(5) |> (Option apply _)
      qres <- data.Queries.getLabelingTaskByHighestRank(num, greaterThanScore) |> (Option apply _)
      res <- qres.map(_.map{
              case WithArticleId(aid, WithScore(art, score)) => LabelingTaskItem(aid, score, art)
             }) |> (Option apply _)
    } yield res.transact(tx)
  }
}
