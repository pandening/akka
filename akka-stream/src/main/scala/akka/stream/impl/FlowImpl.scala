/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.immutable
import scala.concurrent.{ Future, Promise }
import scala.util.Try
import org.reactivestreams.{ Publisher, Subscriber }
import Ast.{ AstNode, Transform }
import akka.stream.{ OverflowStrategy, FlowMaterializer, Transformer }
import akka.stream.{ FlattenStrategy, FlowMaterializer, Transformer }
import akka.stream.scaladsl.Flow
import scala.util.Success
import scala.util.Failure
import akka.stream.scaladsl.Duct
import scala.concurrent.duration.FiniteDuration
import akka.stream.TimerTransformer
import akka.util.Collections.EmptyImmutableSeq

/**
 * INTERNAL API
 */
private[akka] case class FlowImpl[I, O](publisherNode: Ast.PublisherNode[I], ops: List[Ast.AstNode]) extends Flow[O] with Builder[O] {
  import Ast._

  type Thing[T] = Flow[T]

  // Storing ops in reverse order
  override protected def andThen[U](op: Ast.AstNode): Flow[U] = this.copy(ops = op :: ops)

  override def append[U](duct: Duct[_ >: O, U]): Flow[U] =
    copy(ops = duct.ops ++: ops)

  override def appendJava[U](duct: akka.stream.javadsl.Duct[_ >: O, U]): Flow[U] =
    copy(ops = duct.ops ++: ops)

  override def toFuture()(implicit materializer: FlowMaterializer): Future[O] = {
    val p = Promise[O]()
    transform("toFuture", () ⇒ new Transformer[O, Unit] {
      var done = false
      override def onNext(in: O) = { p success in; done = true; Nil }
      override def onError(e: Throwable) = { p failure e }
      override def isComplete = done
      override def onTermination(e: Option[Throwable]) = { p.tryFailure(new NoSuchElementException("empty stream")); Nil }
    }).consume()
    p.future
  }

  override def consume()(implicit materializer: FlowMaterializer): Unit =
    produceTo(new BlackholeSubscriber[Any](materializer.settings.maximumInputBufferSize))

  override def onComplete(callback: Try[Unit] ⇒ Unit)(implicit materializer: FlowMaterializer): Unit =
    transform("onComplete", () ⇒ new Transformer[O, Unit] {
      override def onNext(in: O) = Nil
      override def onError(e: Throwable) = {
        callback(Failure(e))
        throw e
      }
      override def onTermination(e: Option[Throwable]) = {
        callback(Builder.SuccessUnit)
        Nil
      }
    }).consume()

  override def toPublisher[U >: O]()(implicit materializer: FlowMaterializer): Publisher[U] = materializer.toPublisher(publisherNode, ops)

  override def produceTo(subscriber: Subscriber[_ >: O])(implicit materializer: FlowMaterializer): Unit =
    toPublisher().subscribe(subscriber.asInstanceOf[Subscriber[O]])

  override def foreach(c: O ⇒ Unit)(implicit materializer: FlowMaterializer): Future[Unit] =
    foreachTransform(c).toFuture()
}

/**
 * INTERNAL API
 */
private[akka] case class DuctImpl[In, Out](ops: List[Ast.AstNode]) extends Duct[In, Out] with Builder[Out] {

  type Thing[T] = Duct[In, T]

  // Storing ops in reverse order
  override protected def andThen[U](op: Ast.AstNode): Duct[In, U] = this.copy(ops = op :: ops)

  override def append[U](duct: Duct[_ >: Out, U]): Duct[In, U] =
    copy(ops = duct.ops ++: ops)

  override def appendJava[U](duct: akka.stream.javadsl.Duct[_ >: Out, U]): Duct[In, U] =
    copy(ops = duct.ops ++: ops)

  override def produceTo[U >: Out](subscriber: Subscriber[U])(implicit materializer: FlowMaterializer): Subscriber[In] =
    materializer.ductProduceTo(subscriber, ops)

  override def consume()(implicit materializer: FlowMaterializer): Subscriber[In] =
    produceTo(new BlackholeSubscriber[Any](materializer.settings.maximumInputBufferSize))

  override def onComplete(callback: Try[Unit] ⇒ Unit)(implicit materializer: FlowMaterializer): Subscriber[In] =
    transform("onComplete", () ⇒ new Transformer[Out, Unit] {
      override def onNext(in: Out) = Nil
      override def onError(e: Throwable) = {
        callback(Failure(e))
        throw e
      }
      override def onTermination(e: Option[Throwable]) = {
        callback(Builder.SuccessUnit)
        Nil
      }
    }).consume()

  override def build[U >: Out]()(implicit materializer: FlowMaterializer): (Subscriber[In], Publisher[U]) =
    materializer.ductBuild(ops)

  override def foreach(c: Out ⇒ Unit)(implicit materializer: FlowMaterializer): (Subscriber[In], Future[Unit]) = {
    val p = Promise[Unit]()
    val s = foreachTransform(c).onComplete {
      case Success(_) ⇒ p.success(())
      case Failure(e) ⇒ p.failure(e)
    }
    (s, p.future)
  }

}

/**
 * INTERNAL API
 */
private[akka] object Builder {
  val SuccessUnit = Success[Unit](())
  private val ListOfUnit = List(())
  private case object TakeWithinTimerKey
  private case object DropWithinTimerKey
  private case object GroupedWithinTimerKey

  private val takeCompletedTransformer: Transformer[Any, Any] = new Transformer[Any, Any] {
    override def onNext(elem: Any) = Nil
    override def isComplete = true
  }

  private val identityTransformer: Transformer[Any, Any] = new Transformer[Any, Any] {
    override def onNext(elem: Any) = List(elem)
  }
}

/**
 * INTERNAL API
 * Builder of `Flow` or `Duct` things
 */
private[akka] trait Builder[Out] {
  import Builder._
  import akka.stream.impl.Ast._
  import scala.language.higherKinds

  type Thing[T]

  protected def andThen[U](op: Ast.AstNode): Thing[U]

  def map[U](f: Out ⇒ U): Thing[U] =
    transform("map", () ⇒ new Transformer[Out, U] {
      override def onNext(in: Out) = List(f(in))
    })

  def mapFuture[U](f: Out ⇒ Future[U]): Thing[U] =
    andThen(MapFuture(f.asInstanceOf[Any ⇒ Future[Any]]))

  def filter(p: Out ⇒ Boolean): Thing[Out] =
    transform("filter", () ⇒ new Transformer[Out, Out] {
      override def onNext(in: Out) = if (p(in)) List(in) else Nil
    })

  def collect[U](pf: PartialFunction[Out, U]): Thing[U] =
    transform("collect", () ⇒ new Transformer[Out, U] {
      override def onNext(in: Out) = if (pf.isDefinedAt(in)) List(pf(in)) else Nil
    })

  def foreachTransform(c: Out ⇒ Unit): Thing[Unit] =
    transform("foreach", () ⇒ new Transformer[Out, Unit] {
      override def onNext(in: Out) = { c(in); Nil }
      override def onTermination(e: Option[Throwable]) = ListOfUnit
    })

  def fold[U](zero: U)(f: (U, Out) ⇒ U): Thing[U] =
    transform("fold", () ⇒ new FoldTransformer[U](zero, f))

  // Without this class compiler complains about
  // "Parameter type in structural refinement may not refer to an abstract type defined outside that refinement"
  class FoldTransformer[S](var state: S, f: (S, Out) ⇒ S) extends Transformer[Out, S] {
    override def onNext(in: Out): immutable.Seq[S] = { state = f(state, in); Nil }
    override def onTermination(e: Option[Throwable]): immutable.Seq[S] = List(state)
  }

  def drop(n: Int): Thing[Out] =
    transform("drop", () ⇒ new Transformer[Out, Out] {
      var delegate: Transformer[Out, Out] =
        if (n == 0) identityTransformer.asInstanceOf[Transformer[Out, Out]]
        else new Transformer[Out, Out] {
          var c = n
          override def onNext(in: Out) = {
            c -= 1
            if (c == 0)
              delegate = identityTransformer.asInstanceOf[Transformer[Out, Out]]
            Nil
          }
        }

      override def onNext(in: Out) = delegate.onNext(in)
    })

  def dropWithin(d: FiniteDuration): Thing[Out] =
    timerTransform("dropWithin", () ⇒ new TimerTransformer[Out, Out] {
      scheduleOnce(DropWithinTimerKey, d)

      var delegate: Transformer[Out, Out] =
        new Transformer[Out, Out] {
          override def onNext(in: Out) = Nil
        }

      override def onNext(in: Out) = delegate.onNext(in)
      override def onTimer(timerKey: Any) = {
        delegate = identityTransformer.asInstanceOf[Transformer[Out, Out]]
        Nil
      }
    })

  def take(n: Int): Thing[Out] =
    transform("take", () ⇒ new Transformer[Out, Out] {
      var delegate: Transformer[Out, Out] =
        if (n == 0) takeCompletedTransformer.asInstanceOf[Transformer[Out, Out]]
        else new Transformer[Out, Out] {
          var c = n
          override def onNext(in: Out) = {
            c -= 1
            if (c == 0)
              delegate = takeCompletedTransformer.asInstanceOf[Transformer[Out, Out]]
            List(in)
          }
        }

      override def onNext(in: Out) = delegate.onNext(in)
      override def isComplete = delegate.isComplete
    })

  def takeWithin(d: FiniteDuration): Thing[Out] =
    timerTransform("takeWithin", () ⇒ new TimerTransformer[Out, Out] {
      scheduleOnce(TakeWithinTimerKey, d)

      var delegate: Transformer[Out, Out] = identityTransformer.asInstanceOf[Transformer[Out, Out]]

      override def onNext(in: Out) = delegate.onNext(in)
      override def isComplete = delegate.isComplete
      override def onTimer(timerKey: Any) = {
        delegate = takeCompletedTransformer.asInstanceOf[Transformer[Out, Out]]
        Nil
      }
    })

  def prefixAndTail[U >: Out](n: Int): Thing[(immutable.Seq[Out], Publisher[U])] = andThen(PrefixAndTail(n))

  def grouped(n: Int): Thing[immutable.Seq[Out]] =
    transform("grouped", () ⇒ new Transformer[Out, immutable.Seq[Out]] {
      var buf: Vector[Out] = Vector.empty
      override def onNext(in: Out) = {
        buf :+= in
        if (buf.size == n) {
          val group = buf
          buf = Vector.empty
          List(group)
        } else
          Nil
      }
      override def onTermination(e: Option[Throwable]) = if (buf.isEmpty) Nil else List(buf)
    })

  def groupedWithin(n: Int, d: FiniteDuration): Thing[immutable.Seq[Out]] =
    timerTransform("groupedWithin", () ⇒ new TimerTransformer[Out, immutable.Seq[Out]] {
      schedulePeriodically(GroupedWithinTimerKey, d)
      var buf: Vector[Out] = Vector.empty

      override def onNext(in: Out) = {
        buf :+= in
        if (buf.size == n) {
          // start new time window
          schedulePeriodically(GroupedWithinTimerKey, d)
          emitGroup()
        } else Nil
      }
      override def onTermination(e: Option[Throwable]) = if (buf.isEmpty) Nil else List(buf)
      override def onTimer(timerKey: Any) = emitGroup()
      private def emitGroup(): immutable.Seq[immutable.Seq[Out]] =
        if (buf.isEmpty) EmptyImmutableSeq
        else {
          val group = buf
          buf = Vector.empty
          List(group)
        }
    })

  def mapConcat[U](f: Out ⇒ immutable.Seq[U]): Thing[U] =
    transform("mapConcat", () ⇒ new Transformer[Out, U] {
      override def onNext(in: Out) = f(in)
    })

  def transform[U](name: String, mkTransformer: () ⇒ Transformer[Out, U]): Thing[U] =
    andThen(Transform(name, mkTransformer.asInstanceOf[() ⇒ Transformer[Any, Any]]))

  def timerTransform[U](name: String, mkTransformer: () ⇒ TimerTransformer[Out, U]): Thing[U] =
    andThen(TimerTransform(name, mkTransformer.asInstanceOf[() ⇒ TimerTransformer[Any, Any]]))

  def zip[O2](other: Publisher[O2]): Thing[(Out, O2)] = andThen(Zip(other.asInstanceOf[Publisher[Any]]))

  def concat[U >: Out](next: Publisher[U]): Thing[U] = andThen(Concat(next.asInstanceOf[Publisher[Any]]))

  def merge[U >: Out](other: Publisher[_ <: U]): Thing[U] = andThen(Merge(other.asInstanceOf[Publisher[Any]]))

  def splitWhen[U >: Out](p: (Out) ⇒ Boolean): Thing[Publisher[U]] = andThen(SplitWhen(p.asInstanceOf[Any ⇒ Boolean]))

  def groupBy[K, U >: Out](f: (Out) ⇒ K): Thing[(K, Publisher[U])] = andThen(GroupBy(f.asInstanceOf[Any ⇒ Any]))

  def broadcast(other: Subscriber[_ >: Out]): Thing[Out] = andThen(Broadcast(other.asInstanceOf[Subscriber[Any]]))

  def conflate[S](seed: Out ⇒ S, aggregate: (S, Out) ⇒ S): Thing[S] =
    andThen(Conflate(seed.asInstanceOf[Any ⇒ Any], aggregate.asInstanceOf[(Any, Any) ⇒ Any]))

  def expand[S, U](seed: Out ⇒ S, extrapolate: S ⇒ (U, S)): Thing[U] =
    andThen(Expand(seed.asInstanceOf[Any ⇒ Any], extrapolate.asInstanceOf[Any ⇒ (Any, Any)]))

  def buffer(size: Int, overflowStrategy: OverflowStrategy): Thing[Out] = {
    require(size > 0, s"Buffer size must be larger than zero but was [$size]")
    andThen(Buffer(size, overflowStrategy))
  }

  def flatten[U](strategy: FlattenStrategy[Out, U]): Thing[U] = strategy match {
    case _: FlattenStrategy.Concat[Out] ⇒ andThen(ConcatAll)
    case _                              ⇒ throw new IllegalArgumentException(s"Unsupported flattening strategy [${strategy.getClass.getSimpleName}]")
  }

}

