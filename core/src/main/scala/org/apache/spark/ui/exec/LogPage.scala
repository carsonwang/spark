/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ui.exec

import java.io.File
import javax.servlet.http.HttpServletRequest

import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.spark.Logging
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.ui.{UIUtils, WebUIPage}
import org.apache.spark.util.Utils

import scala.xml.Node
import scala.sys.process._


private[ui] class LogPage(
    parent: ExecutorsTab)
  extends WebUIPage("logPage") with Logging {

  def render(request: HttpServletRequest): Seq[Node] = {
    val defaultBytes = 100 * 1024
    val appId = parent.appId
    val containerId = Option(request.getParameter("containerId"))
    val nodeAddress = Option(request.getParameter("nodeAddress"))
    val appOwner = Option(request.getParameter("appOwner"))
    val offset = Option(request.getParameter("offset")).map(_.toLong)
    val byteLength = Option(request.getParameter("byteLength")).map(_.toInt).getOrElse(defaultBytes)

    if (!(containerId.isDefined && nodeAddress.isDefined && appOwner.isDefined)) {
      throw new Exception("Request must specify appId, containerId and appOwner!")
    }

    val logPath = s"/tmp/${appId}_${containerId.get}.log"

    val hadoopConfiguration = SparkHadoopUtil.get.newConfiguration(parent.conf)
    val yarnConf = new YarnConfiguration(hadoopConfiguration)



    val (logText, startByte, endByte, logLength) = getLog(
      logPath, appId, containerId.get, nodeAddress.get, appOwner.get, offset, byteLength)
    val range = <span>Bytes {startByte.toString} - {endByte.toString} of {logLength}</span>

    val backButton =
      if (startByte > 0) {
        <a href={"?containerId=%s&nodeAddress=%s&appOwner=%s&offset=%s&byteLength=%s"
          .format(containerId.get, nodeAddress.get, appOwner.get,
            math.max(startByte - byteLength, 0), byteLength)}>
          <button type="button" class="btn btn-default">
            Previous {Utils.bytesToString(math.min(byteLength, startByte))}
          </button>
        </a>
      } else {
        <button type="button" class="btn btn-default" disabled="disabled">
          Previous 0 B
        </button>
      }

    val nextButton =
      if (endByte < logLength) {
        <a href={"?containerId=%s&nodeAddress=%s&appOwner=%s&offset=%s&byteLength=%s"
          .format(containerId.get, nodeAddress.get, appOwner.get, endByte, byteLength)}>
          <button type="button" class="btn btn-default">
            Next {Utils.bytesToString(math.min(byteLength, logLength - endByte))}
          </button>
        </a>
      } else {
        <button type="button" class="btn btn-default" disabled="disabled">
          Next 0 B
        </button>
      }

    val content =
      <html>
        <body>
          <div>
            <div style="float:left; margin-right:10px">{backButton}</div>
            <div style="float:left;">{range}</div>
            <div style="float:right; margin-left:10px">{nextButton}</div>
          </div>
          <br />
          <div style="height:500px; overflow:auto; padding:5px;">
            <pre>{logText}</pre>
          </div>
        </body>
      </html>
    UIUtils.basicSparkPage(content, "Log page for " + appId)
  }

  /** Get the part of the log files given the offset and desired length of bytes */
  private def getLog(
    filePath: String,
    appId: String,
    containerId: String,
    nodeAddress: String,
    appOwner: String,
    offsetOption: Option[Long],
    byteLength: Int
  ): (String, Long, Long, Long) = {
    try {
      var file = new File(filePath)
      if (!file.exists()) {
        val cmd = s"yarn logs -applicationId $appId -containerId $containerId -nodeAddress " +
          s"$nodeAddress -appOwner $appOwner"
        cmd #> new File(filePath) ! ProcessLogger(line => logInfo("ProcessLogger: " + line))
        file = new File(filePath)
      }

      val offset = offsetOption.getOrElse(file.length - byteLength)
      val startIndex = {
        if (offset < 0) {
          0L
        } else if (offset > file.length) {
          file.length
        } else {
          offset
        }
      }
      val endIndex = math.min(startIndex + byteLength, file.length)
      logDebug(s"Getting log from $startIndex to $endIndex")
      val logText = Utils.offsetBytes(Seq(file), startIndex, endIndex)
      logDebug(s"Got log of length ${logText.length} bytes")
      (logText, startIndex, endIndex, file.length)
    } catch {
      case e: Exception =>
        logError(s"Error getting log $filePath ", e)
        ("The log does not exist. Please make sure log aggregation is enabled in Yarn mode", 0, 0, 0)
    }
  }

}
