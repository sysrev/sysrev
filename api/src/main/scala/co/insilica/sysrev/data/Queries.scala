package co.insilica.sysrev.data

import co.insilica.auth.Types.UserId
import co.insilica.sysrev.data.Types.ReviewTag
import doobie.imports._

import doobie.contrib.postgresql.sqlstate.class23.UNIQUE_VIOLATION

import scalaz.{\/, -\/}

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

}