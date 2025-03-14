/*
 * Copyright 2011-2025 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.http.check.body

import io.gatling.commons.validation._
import io.gatling.core.check.{ CheckMaterializer, Preparer }
import io.gatling.core.check.xpath.{ XPathCheckType, XmlParsers }
import io.gatling.http.check.{ HttpCheck, HttpCheckMaterializer }
import io.gatling.http.check.HttpCheckScope.Body
import io.gatling.http.response.Response

import net.sf.saxon.s9api.XdmNode

object HttpBodyXPathCheckMaterializer {
  private val ErrorMapper: String => String = "Could not parse response into a DOM Document: " + _

  val Instance: CheckMaterializer[XPathCheckType, HttpCheck, Response, XdmNode] = {
    val preparer: Preparer[Response, XdmNode] = response =>
      safely(ErrorMapper) {
        XmlParsers.parse(response.body.stream, response.body.charset).success
      }

    new HttpCheckMaterializer[XPathCheckType, XdmNode](Body, preparer)
  }
}
