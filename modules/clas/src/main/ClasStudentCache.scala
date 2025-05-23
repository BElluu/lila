package lila.clas

import akka.stream.Materializer
import akka.stream.scaladsl.*
import bloomfilter.mutable.BloomFilter
import reactivemongo.akkastream.cursorProducer

import lila.db.dsl.*

final class ClasStudentCache(colls: ClasColls, settingStore: lila.memo.SettingStore.Builder)(using
    scheduler: Scheduler
)(using Executor, Materializer):

  private val falsePositiveRate = 0.00003
  // Stick to [String], it does unsafe operations that don't play well with opaque types
  private var bloomFilter: BloomFilter[String] =
    BloomFilter[String](100, falsePositiveRate) // temporary empty filter

  lazy val useSecondary = settingStore[Boolean](
    "clasStudentCacheUseSecondary",
    default = true,
    text = "Use db sec for student cache".some
  )

  def isStudent(userId: UserId) = bloomFilter.mightContain(userId.value)

  def addStudent(userId: UserId): Unit = bloomFilter.add(userId.value)

  private def rebuildBloomFilter(): Unit =
    colls.student
      .countSel("archived".$exists(false))
      .foreach: count =>
        val nextBloom = BloomFilter[String](count + 1, falsePositiveRate)
        colls.student
          .find($doc("archived".$exists(false)), $doc("userId" -> true, "_id" -> false).some)
          .cursor[Bdoc](if useSecondary.get() then ReadPref.sec else ReadPref.priTemp)
          .documentSource()
          .throttle(300, 1.second)
          .runWith(Sink.fold[Int, Bdoc](0): (counter, doc) =>
            if counter % 1000 == 0 then
              logger.info(s"ClasStudentCache.rebuild $counter with sec=${useSecondary.get()}")
            doc.string("userId").foreach(nextBloom.add)
            counter + 1)
          .addEffect: nb =>
            lila.mon.clas.student.bloomFilter.count.update(nb)
            bloomFilter.dispose()
            bloomFilter = nextBloom
          .monSuccess(_.clas.student.bloomFilter.fu)

  scheduler.scheduleWithFixedDelay(71.seconds, 24.hours): () =>
    rebuildBloomFilter()
