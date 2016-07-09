package co.insilica
package sysrev

import doobie.imports.ConnectionIO

import scalaz._
import Scalaz._

import language.higherKinds

object Converters{

  /**
    * lift a functor of T into a functor of option of t, and apply OptionT transform
    * Useful in for comprehensions when you have a ConnectionIO[T] and you need a OptionT[ConnectionIO, T]
    * Meaning ConnectionIO[T] -> ConnectionIO[Option[T]] -> OptionT[ConnectionIO, T]
    *
    * @return
    */
  def liftOption[F[_], T](c : F[T])(implicit FF: Functor[F]): OptionT[F, T] = FF.map(c)(_.point[Option]) |> (OptionT apply _)

  /**
    * Convenience alias just for [[ConnectionIO]]
    */
  def liftOptionC[T](c: ConnectionIO[T]) = liftOption[ConnectionIO, T](c)
}

