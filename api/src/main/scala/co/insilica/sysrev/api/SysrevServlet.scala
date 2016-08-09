package co.insilica.sysrev
package api

import co.insilica.auth.Types.UserId
import co.insilica.auth.{User, ErrorResult, AuthStack, AuthServlet}
import co.insilica.apistack.{ApiStack, Result, ResultWrapSupport}
import co.insilica.sysrev.data.{Tags, ReviewTag}
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
  override protected implicit lazy val transactor: Transactor[Task] = SysrevConfig.default.transactor
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
  def errorResult : ErrorResult[Any] = ErrorResult("Bad request")

  def errorTask : Task[ErrorResult[Any]] = Task.now(errorResult)

  def handleOT(action: Option[Task[Any]]): Future[Any] = action.map(_.map(Result apply _)).getOrElse(errorTask).runFuture

  def getOT(route: RouteTransformer*)(action: => Option[Task[Any]]): Route = get(route: _*)(handleOT(action))

  def postOT(route: RouteTransformer*)(action: => Option[Task[Any]]): Route = post(route: _*)(handleOT(action))

  def getO(route: RouteTransformer*)(action: => Option[Any]): Route =
    get(route: _*)(action.map(Result apply _).getOrElse(errorResult))
}


class SysrevServlet extends AuthStack with FutureSupport with ResultWrapSupport with RouteHelpers {
  implicit val cfg = SysrevConfig(".insilica/sysrev/config_api.json")
  val tx = cfg.transactor
  override protected implicit lazy val transactor: Transactor[Task] = tx

  type WithUserId[T] = co.insilica.auth.Types.WithId[T]
  val WithUserId = co.insilica.auth.WithAnyId

  def getRankedPage(p: Long = 0L) : Task[Map[ArticleId, WithScore[ArticleWithoutKeywords]]] =
    Queries.rankedArticlesPage(p).transact(tx).map{ xs =>
      xs.map(WithArticleId.unapply(_).get).toMap
    }

  getT("/ranking/:page")(params("page").parseLong.toOption.map(getRankedPage).getOrElse(getRankedPage(0)))

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

  getO("/user")(userOption.map(u => CurrentUser(u.id, u.t.t)))

  // Expects a [[ReviewTag]] as json, saves or updates the tag, and sends back the id of the tag.
  postOT("/tag")((userOption |@| parsedBody.extractOpt[ReviewTag])((u, r) => data.Queries.tagArticle(u.id, r).transact(tx)))

  postOT("/tags")((userOption |@| parsedBody.extractOpt[Tags])((u, ts) =>
    // Doing this as N different transactions. There should be a way to sequence first and then transact,
    // doing the update as a single transaction. Right now, making this switch causes only the last item
    // to get updated.
    data.Queries.updateTagsForArticle(u.id, ts.tags).map(_.transact(tx)).sequenceU
  ))

  getOT("/users")(userOption *> Option(data.Queries.usersSummaryData.transact(tx)))

  getOT("/label-task/:num"){
    val greaterThanScore : Double = params.get("greaterThanScore").flatMap(_.parseDouble.toOption).getOrElse(0.0)
    for {
      _ <- userOption
      num <- params("num").parseLong.toOption.getOrElse(5L) |> (Option apply _)
      qres <- data.Queries.getLabelingTaskByHighestRank(num, greaterThanScore) |> (Option apply _)
      res <- qres.map(_.map{
              case WithArticleId(aid, WithScore(art, score)) => LabelingTaskItem(aid, score, art)
             }) |> (Option apply _)
    } yield res.transact(tx)
  }
}
