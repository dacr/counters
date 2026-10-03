/*
 * Copyright 2020-2026 David Crosson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package counters.tools

import com.github.plokhotnyuk.jsoniter_scala.core.*
import sttp.tapir.{Schema, SchemaType}

import java.net.{URI, URL}
import java.time.Instant

/** Shared jsoniter codecs and tapir schemas for types which are not handled out of the box, or which must keep the historical json
  * representation (instants are stored and exposed as epoch milliseconds).
  */
object JsonCodecs {

  given instantCodec: JsonValueCodec[Instant] = new JsonValueCodec[Instant] {
    override def decodeValue(in: JsonReader, default: Instant): Instant = Instant.ofEpochMilli(in.readLong())
    override def encodeValue(x: Instant, out: JsonWriter): Unit         = out.writeVal(x.toEpochMilli)
    override def nullValue: Instant                                     = null
  }

  given urlCodec: JsonValueCodec[URL] = new JsonValueCodec[URL] {
    override def decodeValue(in: JsonReader, default: URL): URL = {
      val value = in.readString(null)
      try URI(value).toURL
      catch { case _: Exception => in.decodeError(s"invalid URL $value") }
    }
    override def encodeValue(x: URL, out: JsonWriter): Unit = out.writeVal(x.toString)
    override def nullValue: URL                             = null
  }

  given instantSchema: Schema[Instant] =
    Schema(SchemaType.SInteger[Instant](), format = Some("int64"), description = Some("timestamp as epoch milliseconds"))

  given urlSchema: Schema[URL] = Schema.string[URL].format("uri")
}
