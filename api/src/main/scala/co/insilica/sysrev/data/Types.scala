package co.insilica.sysrev.data

import co.insilica.sysrev.relationalImporter.Types._

object Types{
  case class ReviewTag(articleId: ArticleId, criteriaId: CriteriaId, value: Option[Boolean])

}