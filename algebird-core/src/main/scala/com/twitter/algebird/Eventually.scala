/*
Copyright 2013 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.twitter.algebird

/**
 * Classes that support algebraic structures with dynamic switching between
 * two representations, the original type O and the eventual type E.
 * In the case of Semigroup, we specify
 * - Two Semigroups eventualSemigroup and originalSemigroup
 * - A Semigroup homomorphism convert: O => E
 * - A conditional mustConvert: O => Boolean
 * Then we get a Semigroup[Either[E,O]], where:
 *   Left(x)  + Left(y)  = Left(x+y)
 *   Left(x)  + Right(y) = Left(x+convert(y))
 *   Right(x) + Left(y)  = Left(convert(x)+y)
 *   Right(x) + Right(y) = Left(convert(x+y)) if mustConvert(x+y)
 *                         Right(x+y) otherwise.
 * EventuallyMonoid, EventuallyGroup, and EventuallyRing are defined analogously,
 * with the contract that convert respect the appropriate structure.
 *
 * @param E eventual type
 * @param O original type
 */
class EventuallySemigroup[E, O](convert: O => E)(mustConvert: O => Boolean)
  (implicit eventualSemigroup: Semigroup[E], originalSemigroup: Semigroup[O]) extends Semigroup[Either[E, O]] {

  import scala.collection.mutable.Buffer

  override def plus(x: Either[E, O], y: Either[E, O]) = {
    x match {
      case Left(xe) => y match {
        case Left(ye) => left(Semigroup.plus(xe, ye))
        case Right(yo) => left(Semigroup.plus(xe, convert(yo)))
      }
      case Right(xo) => y match {
        case Left(ye) => left(Semigroup.plus(convert(xo), ye))
        case Right(yo) => conditionallyConvert(Semigroup.plus(xo, yo))
      }
    }
  }

  private val maxBuffer = 1000

  /**
   * used to avoid materializing the entire input in memory
   */
  private def checkSize[T](buffer: Buffer[T])(implicit sg: Semigroup[T]) {
    if (buffer.size > maxBuffer) {
      val sum = Semigroup.sumOption(buffer)
      buffer.clear
      sum.map(buffer += _)
    }
  }

  override def sumOption(iter: TraversableOnce[Either[E, O]]): Option[Either[E, O]] = {
    iter.foldLeft[Either[Buffer[E], Buffer[O]]] (Right(Buffer[O]())) ((buffer, v) => {
      // turns the list of either into an either of lists
      buffer match {
        case Left(be) => {
          checkSize(be)
          v match {
            case Left(ve) => be += ve
            case Right(vo) => be += convert(vo)
          }
          buffer // left stays left we just add to the buffer and convert if needed
        }
        case Right(bo) => {
          checkSize(bo)
          v match {
            case Left(ve) => { // one left value => the right list needs to be converted
              val newBuffer = Buffer[E]()
              if (bo.size > 0) {
                Semigroup.sumOption(bo).map((sum) => newBuffer += convert(sum))
              }
              newBuffer += ve
              Left(newBuffer)
            }
            case Right(vo) => {
              bo += vo
              buffer // otherwise stays right, just add to the buffer
            }
          }
        }
      }
    }) match { // finally apply sumOption accordingly
       case Left(be) => Semigroup.sumOption(be).map(left(_))
       case Right(bo) => Semigroup.sumOption(bo).map(conditionallyConvert(_)) // and optionally convert
    }
  }

  def conditionallyConvert(o: O) = {
    if (mustConvert(o)) {
      left(convert(o))
    } else {
      Right(o)
    }
  }

  // Overriden by EventuallyGroup to ensure that the group laws are obeyed.
  def left(e: E): Either[E, O] = Left(e)

}

/**
 * @see EventuallySemigroup
 */
class EventuallyMonoid[E, O](convert: O => E)(mustConvert: O => Boolean)
  (implicit lSemigroup: Semigroup[E], rMonoid: Monoid[O]) extends EventuallySemigroup[E, O](convert)(mustConvert)
  with Monoid[Either[E, O]] {

  override def zero = Right(Monoid.zero[O])

}

/**
 * @see EventuallySemigroup
 */
class EventuallyGroup[E, O](convert: O => E)(mustConvert: O => Boolean)
  (implicit lGroup: Group[E], rGroup: Group[O]) extends EventuallyMonoid[E, O](convert)(mustConvert)
  with Group[Either[E, O]] {

  override def negate(x: Either[E, O]) = {
    x match {
      case Left(xe) => left(Group.negate(xe))
      case Right(xo) => Right(Group.negate(xo))
    }
  }

  override def left(e: E) = if (Monoid.isNonZero(e)) Left(e) else zero

}

/**
 * @see EventuallySemigroup
 */
class EventuallyRing[E, O](convert: O => E)(mustConvert: O => Boolean)
  (implicit lRing: Ring[E], rRing: Ring[O]) extends EventuallyGroup[E, O](convert)(mustConvert)
  with Ring[Either[E, O]] {

  override def one = Right(Ring.one[O])

  override def times(x: Either[E, O], y: Either[E, O]) = {
    x match {
      case Left(xe) => y match {
        case Left(ye) => left(Ring.times(xe, ye))
        case Right(yo) => left(Ring.times(xe, convert(yo)))
      }
      case Right(xo) => y match {
        case Left(ye) => left(Ring.times(convert(xo), ye))
        case Right(yo) => conditionallyConvert(Ring.times(xo, yo))
      }
    }
  }

}
