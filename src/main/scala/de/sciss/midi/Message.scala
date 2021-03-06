/*
 *  Message.scala
 *  (ScalaMIDI)
 *
 *  Copyright (c) 2013-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.midi

import javax.sound.{midi => j}
import j.ShortMessage._
import annotation.switch
import collection.immutable.{IndexedSeq => IIdxSeq}
import util.control.NonFatal

object Message {
  /** Converts a Java MIDI message to a ScalaMIDI message if possible.
    * Unlike `fromJava`, this returns an option, so that currently unsupported messages
    * produce a `None` result instead of throwing an exception.
    *
    * @param m  the Java message to convert
    * @return   the converted message, or `None` if that message is currently not supported
    */
  def fromJavaOption(m: j.MidiMessage): Option[Message] = {
    try { Some(fromJava(m)) } catch { case NonFatal(_) => None }
  }

  /** Converts a Java MIDI message to a ScalaMIDI message.
    * Unlike `fromJavaOption`, this throws an exception if a currently unsupported message is detected.
    *
    * The following messages are currently unsupported
    * - Short messages: Channel pressure (0xD0), MIDI time code (0xF1),
    *   Pitch bend (0xE0), Poly pressure (0xA0), Song position pointer (0xF2), Song select (0xF3),
    *   Start (0xFA), Stop (0xFC), Continue (0xFB), System reset (0xFF), Timing clock (0xF8),
    *   Tune request (0xF6), Active sensing (0xFE)
    * - Meta messages: Sequence number (0x00), Sequencer specific (0x7F)
    *
    * @param m  the Java message to convert
    * @return   the converted message
    */
  def fromJava(m: j.MidiMessage): Message = {
    m match {
      case sm: j.ShortMessage =>
        val channel = sm.getChannel
        (sm.getCommand: @switch) match {
          case NOTE_ON if sm.getData2 > 0 =>
            NoteOn (channel, sm.getData1, sm.getData2)
          case NOTE_OFF =>
            NoteOff (channel, sm.getData1, sm.getData2)
          case NOTE_ON /* if sm.getData2 == 0 */ =>   // retarded MIDI spec: NoteOn with velocity 0 replaces NoteOff
            NoteOff (channel, sm.getData1, sm.getData2)
          case CONTROL_CHANGE =>
            ControlChange(channel, sm.getData1, sm.getData2)
          case PROGRAM_CHANGE =>
            ProgramChange(channel, sm.getData1)

          case _ => unknownMessage(m)
        }
      case mm: j.MetaMessage =>
        import MetaMessage._
        // cf. http://www.omega-art.com/midi/mfiles.html#meta
        // cf. http://www.recordingblogs.com/sa/tabid/88/Default.aspx?topic=MIDI+meta+messages
        (mm.getType: @switch) match {
          case KeySignature.tpe =>
            val arr = mm.getData
            if (arr.length != 2) malformedMessage(m)
            KeySignature(arr(0), KeySignature.Mode(arr(1)))

          case EndOfTrack.tpe =>
            val arr = mm.getData
            if (arr.length != 0) malformedMessage(m)
            EndOfTrack

          case TimeSignature.tpe =>
            val arr             = mm.getData
            if (arr.length != 4) malformedMessage(m)
            val num             = arr(0)
            val denom           = 1 << arr(1)
            val clocksPerMetro  = arr(2)
            val num32perQ       = arr(3)
            TimeSignature(num, denom, clocksPerMetro, num32perQ)

          case Tempo.tpe =>
            val arr = mm.getData
            if (arr.length != 3) malformedMessage(m)
            val microsPerQ  = ((arr(0) & 0xFF) << 16) | ((arr(1) & 0xFF) << 8) | (arr(2) & 0xFF)
            Tempo(microsPerQ)

          case SMPTEOffset.tpe =>
            val arr = mm.getData
            if (arr.length != 5) malformedMessage(m)
            val code = ((arr(0) & 0xFF).toLong << 32) | ((arr(1) & 0xFF).toLong << 24) | ((arr(2) & 0xFF) << 16) |
              ((arr(3) & 0xFF) << 8) | (arr(4) & 0xFF)
            SMPTEOffset(code)

          case TrackName     .tpe => TrackName     (metaString(mm))
          case InstrumentName.tpe => InstrumentName(metaString(mm))
          case Copyright     .tpe => Copyright     (metaString(mm))
          case Lyrics        .tpe => Lyrics        (metaString(mm))
          case Marker        .tpe => Marker        (metaString(mm))
          case CuePoint      .tpe => CuePoint      (metaString(mm))

          case _ => unknownMessage(m)
        }

      case xm: j.SysexMessage =>
        SysEx(xm.getData.toIndexedSeq)
    }
  }

  private def metaString(mm: j.MetaMessage) = new String(mm.getData, "UTF-8")

  @inline private def unknownMessage(m: j.MidiMessage): Nothing =
    throw new IllegalArgumentException("Unsupported MIDI message " +
      m.getMessage.map(b => (b & 0xFF).toHexString).mkString("[", ",", "]"))

  @inline private def malformedMessage(m: j.MidiMessage): Nothing =
    throw new IllegalArgumentException("Malformed MIDI message " +
      m.getMessage.map(b => (b & 0xFF).toHexString).mkString("[", ",", "]"))

  sealed trait ChannelVoice extends Message {
    def channel: Int
  }
}
sealed trait Message {
  def toJava: j.MidiMessage
}
final case class NoteOn(channel: Int, pitch: Int, velocity: Int) extends Message.ChannelVoice {
  override def toString = s"$productPrefix(channel = $channel, pitch = $pitch, velocity = $velocity)"

  def toJava: j.MidiMessage = {
    val res = new j.ShortMessage
    res.setMessage(NOTE_ON, channel, pitch, velocity)
    res
  }
}
final case class NoteOff(channel: Int, pitch: Int, velocity: Int) extends Message.ChannelVoice {
  override def toString = s"$productPrefix(channel = $channel, pitch = $pitch, velocity = $velocity)"

  def toJava: j.MidiMessage = {
    val res = new j.ShortMessage
    res.setMessage(NOTE_OFF, channel, pitch, velocity)
    res
  }
}
final case class ControlChange(channel: Int, num: Int, value: Int) extends Message.ChannelVoice {
  override def toString = s"$productPrefix(channel = $channel, num = $num, value = $value)"

  def toJava: j.MidiMessage = {
    val res = new j.ShortMessage
    res.setMessage(CONTROL_CHANGE, channel, num, value)
    res
  }
}
final case class ProgramChange(channel: Int, patch: Int) extends Message.ChannelVoice {
  override def toString = s"$productPrefix(channel = $channel, patch = $patch)"

  def toJava: j.MidiMessage = {
    val res = new j.ShortMessage
    res.setMessage(PROGRAM_CHANGE, channel, patch, 0) // last byte is automatically ignored
    res
  }
}
final case class SysEx(data: IIdxSeq[Byte]) extends Message {
  override def toString = {
    s"$productPrefix(data = ${data.take(8).map(_.toInt.toHexString).mkString(" ")}${if (data.size > 8) s"..., size = $data.size" else ""}})"
  }
  
  def toJava: j.MidiMessage = {
    val res = new j.SysexMessage
    val arr = data.toArray
    res.setMessage(arr, arr.length)
    res
  }
}
object MetaMessage {
  object KeySignature {
    final val tpe = 0x59

    object Mode {
      def apply(id: Int): Mode = (id: @switch) match {
        case Minor.id => Minor
        case Major.id => Major
        case _        => throw new IllegalArgumentException(s"Unknown key signature mode $id")
      }
    }
    sealed trait Mode { def id: Int }
    case object Minor extends Mode { final val id = 0 }
    case object Major extends Mode { final val id = 1 }
  }
  final case class KeySignature(shift: Int, mode: KeySignature.Mode) extends MetaMessage {
    override def toString = s"$productPrefix(shift = $shift, $mode)"

    def toJava: j.MidiMessage = {
      val res = new j.MetaMessage
      val arr = new Array[Byte](2)
      arr(0)  = shift.toByte
      arr(1)  = mode.id.toByte
      res.setMessage(KeySignature.tpe, arr, arr.length)
      res
    }
  }

  private final val emptyArray = new Array[Byte](0)

  case object EndOfTrack extends MetaMessage {
    final val tpe = 0x2F
    def toJava: j.MidiMessage = {
      val res = new j.MetaMessage
      res.setMessage(tpe, emptyArray, 0)
      res
    }
  }

  object TimeSignature {
    final val tpe = 0x58

    private def isPowerOfTwo(value: Int) = (value & (value-1)) == 0
  }
  final case class TimeSignature(num: Int, denom: Int, clocksPerMetro: Int, num32perQ: Int = 32) extends MetaMessage {
    if (!TimeSignature.isPowerOfTwo(denom))
      throw new IllegalArgumentException(s"Denominator ($denom) must be a power of two")
//    if (num < 0 || num > 255 || denom < 0)
//      throw new IllegalArgumentException(s"Values out of range (0 <= ${num} < 256, 0 <= ${denom})")

    override def toString = s"$productPrefix($num/$denom, clocksPerMetro = $clocksPerMetro, num32perQ = $num32perQ)"

    def toJava: j.MidiMessage = {
      val res = new j.MetaMessage
      val arr = new Array[Byte](4)
      arr(0)  = num.toByte
      val denomP = {
        var i = 0
        var j = denom
        while (j > 1) {
          j >>= 1
          i  += 1
        }
        i
      }
      arr(1)  = denomP.toByte
      arr(2)  = clocksPerMetro.toByte
      arr(3)  = num32perQ.toByte
      res.setMessage(TimeSignature.tpe, arr, arr.length)
      res
    }
  }

  object Tempo {
    final val tpe = 0x51

    def bpm(value: Double): Tempo = {
      val microsPerQ = (60.0e6 / value + 0.5).toInt
      apply(microsPerQ)
    }
  }
  final case class Tempo(microsPerQ: Int) extends MetaMessage {
    def bpm: Double = 60.0e6 / microsPerQ

    override def toString = s"$productPrefix(µs per 1/4 = $microsPerQ, bpm = ${bpm.toInt})"

    def toJava: j.MidiMessage = {
      val res = new j.MetaMessage
      val arr = new Array[Byte](3)
      arr(0)  = (microsPerQ >> 16).toByte
      arr(1)  = (microsPerQ >> 8).toByte
      arr(2)  =  microsPerQ.toByte
      res.setMessage(Tempo.tpe, arr, arr.length)
      res
    }
  }

  object SMPTEOffset {
    final val tpe = 0x54

    object Format {
      final val _24     = code(0)
      final val _25     = code(1)
      final val _30drop = code(2)
      final val _30     = code(3)

      def value(fps: Double): Format = {
        val code = fps match {
          case 24.0   => 0
          case 25.0   => 1
          case 29.97  => 2
          case 30.0   => 3
          case _      => throw new IllegalArgumentException(s"Unsupported fps $fps")
        }
        new Format(code)
      }

      def code(code: Int): Format = {
        if (code < 0 || code > 3) throw new IllegalArgumentException(s"Unsupported fps code $code")
        new Format(code)
      }
    }
    final case class Format private(code: Int) {
      def value: Double = (code: @switch) match {
        case 0 => 24
        case 1 => 25
        case 2 => 29.97
        case 3 => 30
      }

      override def toString = {
        val v = value
        if (code == 2) v.toString else v.toInt.toString
      }
    }

    def apply(fps: Format, hours: Int, minutes: Int, seconds: Int, frames: Int, subframes: Int): SMPTEOffset = {
      val code = ((fps.code << 6 | hours).toLong << 32) | (minutes.toLong << 24) | (seconds << 16) |
        (frames << 8) | subframes
      new SMPTEOffset(code)
    }
  }
  final case class SMPTEOffset(code: Long) extends MetaMessage {
    import SMPTEOffset.Format

    override def toString = {
      val fpss = fps.toString
      f"$productPrefix(time = $hours%02d:$minutes%02d:$seconds%02d:$frames%02d.$subframes, fps = $fpss)"
    }

    def hours: Int = (code >> 32).toInt & 0x3F

    /** Returns the frame rate encoded in this message.
      * For simplicity this is an integer of the frames per second,
      * where `29` has the special meaning of 30 drop (29.97 fps).
      */
    def fps: Format = Format.code((code >> 38).toInt & 0x03)

    def minutes: Int    = (code.toInt >> 24) & 0xFF
    def seconds: Int    = (code.toInt >> 16) & 0xFF
    def frames: Int     = (code.toInt >>  8) & 0xFF
    def subframes: Int  = code.toInt & 0xFF

    def toJava: j.MidiMessage = {
      val res = new j.MetaMessage
      val arr = new Array[Byte](5)
      arr(0)  = (code >> 32).toByte
      arr(1)  = (code >> 24).toByte
      arr(2)  = (code >> 16).toByte
      arr(3)  = (code >>  8).toByte
      arr(4)  = (code      ).toByte
      res.setMessage(SMPTEOffset.tpe, arr, arr.length)
      res
    }
  }

  object Copyright {
    final val tpe = 0x02
  }
  final case class Copyright(text: String) extends TextLike {
    protected def tpe = Copyright.tpe
  }

  object TrackName {
    final val tpe = 0x03
  }
  final case class TrackName(name: String) extends TextLike {
    def text = name
    protected def tpe = TrackName.tpe
  }

  object InstrumentName {
    final val tpe = 0x04
  }
  final case class InstrumentName(name: String) extends TextLike {
    def text = name
    protected def tpe = InstrumentName.tpe
  }

  object Lyrics {
    final val tpe = 0x05
  }
  final case class Lyrics(text: String) extends TextLike {
    protected def tpe = Lyrics.tpe
  }

  object Marker {
    final val tpe = 0x06
  }
  final case class Marker(name: String) extends TextLike {
    def text = name
    protected def tpe = Marker.tpe
  }

  object CuePoint {
    final val tpe = 0x07
  }
  final case class CuePoint(name: String) extends TextLike {
    def text = name
    protected def tpe = CuePoint.tpe
  }

  sealed trait TextLike extends MetaMessage {
    def text: String
    protected def tpe: Int

    final def toJava: j.MidiMessage = {
      val res = new j.MetaMessage
      val arr = text.getBytes("UTF-8")
      res.setMessage(tpe, arr, arr.length)
      res
    }
  }
}
sealed trait MetaMessage extends Message