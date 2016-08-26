package co.insilica.sysrev.data

import co.insilica.auth.Types.WithId
import co.insilica.sysrev.relationalImporter.queries.ArticleWithoutKeywords
import co.insilica.sysrev.relationalImporter.queries.Types._

case class ReviewTag(articleId: ArticleId, criteriaId: CriteriaId, value: Option[Boolean])
case class Tags(tags: List[ReviewTag])

case class SimpleUser(username: String, profileId: String) {
  def name: String = username.split("@").toList.headOption.getOrElse("")
}

case class ClassifiedArticle(id: ArticleId, article: ArticleWithoutKeywords, include: Option[Boolean], score: Double)
case class UserArticle(user: WithId[SimpleUser], article: ClassifiedArticle)
case class UserArticles(user: WithId[SimpleUser], articles: List[ClassifiedArticle])


object Types {
  type WithUserId[T] = co.insilica.auth.Types.WithId[T]
  val WithUserId = co.insilica.auth.WithAnyId
}
