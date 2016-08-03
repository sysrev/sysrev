package co.insilica.sysrev.data

import co.insilica.auth.Types.{WithId, UserId}
import co.insilica.sysrev.relationalImporter.{WithScore, ArticleWithoutKeywords}
import co.insilica.sysrev.relationalImporter.Types.WithArticleId
import doobie.imports._
import doobie.contrib.postgresql.pgtypes._
import doobie.contrib.postgresql.sqlstate.class23.UNIQUE_VIOLATION

import scalaz._
import Scalaz._
import scalaz.concurrent.Task


object Queries{

  def tagArticleQ(userId: UserId, reviewTag: ReviewTag): Update0 = sql"""
    insert into article_criteria (article_id, criteria_id, answer, user_id)
    values (${reviewTag.articleId}, ${reviewTag.criteriaId}, ${reviewTag.value}, ${userId})
  """.update

  def updateTagArticleQ(userId: UserId, reviewTag: ReviewTag): Update0 = sql"""
    update article_criteria
    set answer = ${reviewTag.value}
    where article_id = ${reviewTag.articleId} and criteria_id = ${reviewTag.criteriaId} and user_id = ${userId}
  """.update

  def unsafeTagArticleQ(userId: UserId, reviewTag: ReviewTag) : ConnectionIO[Int] =
    tagArticleQ(userId, reviewTag).withUniqueGeneratedKeys("article_criteria_id")

  def updateTagArticle(userId: UserId, reviewTag: ReviewTag) : ConnectionIO[Int] = updateTagArticleQ(userId, reviewTag).run

  def tagArticle(userId: UserId, reviewTag: ReviewTag): ConnectionIO[Int] =
    unsafeTagArticleQ(userId, reviewTag).exceptSomeSqlState{
      case UNIQUE_VIOLATION => updateTagArticle(userId, reviewTag)
    }


  def usersSummaryDataQ : Query0[UserArticle] = sql"""
    select              id, email, profileid,
                        article_id, primary_title, secondary_title, abstract, authors, work_type, remote_database_name, year, urls,
                        answer, _2 as score
    from site_user
    left join article_criteria on id = user_id and criteria_id = 1
    left join article using (article_id)
    left join article_ranking on _1 = article_id
    where criteria_id = 1
  """.query[UserArticle]


  def usersSummaryData : ConnectionIO[List[UserArticles]] = usersSummaryDataQ.list.map{ xs =>
    QueryOps.collapse[UserArticle, UserArticles](xs)({
      case (UserArticle(luser, _), UserArticle(ruser, _)) => luser.id == ruser.id
    })({
      case (Some(UserArticles(luser, acc)), UserArticle(_, art)) => UserArticles(luser, art :: acc)
      case (None, UserArticle(ruser, art)) => UserArticles(ruser, List(art))
    })
  }

  def getLabelingTaskByHighestRankQ(num: Long = 10) : Query0[WithArticleId[WithScore[ArticleWithoutKeywords]]] = sql"""
    select article_id, primary_title, secondary_title, abstract, authors, work_type, remote_database_name, year, urls,
           _2 as score
    from article
    left join article_criteria using (article_id)
    left join article_ranking on _1 = article_id
    where criteria_id is null
    order by score desc
    limit $num
  """.query

  def getLabelingTaskByHighestRank(num :Long = 10) : ConnectionIO[List[WithArticleId[WithScore[ArticleWithoutKeywords]]]] =
    getLabelingTaskByHighestRankQ(num).list

}
