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

package io.gatling.http.client.impl;

import io.gatling.http.client.impl.request.WritableRequest;
import io.gatling.http.client.impl.request.WritableRequestBuilder;
import io.gatling.http.client.pool.ChannelPool;
import io.gatling.http.client.util.HttpUtils;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderResultProvider;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HttpAppHandler extends ChannelDuplexHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpAppHandler.class);

  static final IOException PREMATURE_CLOSE =
      new IOException("Premature close") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this;
        }
      };

  private final DefaultHttpClient client;
  private final ChannelPool channelPool;
  private HttpTx tx;
  private boolean httpResponseReceived;

  HttpAppHandler(DefaultHttpClient client, ChannelPool channelPool) {
    this.client = client;
    this.channelPool = channelPool;
  }

  @Override
  public boolean isSharable() {
    return false;
  }

  private void setActive(HttpTx tx) {
    this.tx = tx;
  }

  private void setInactive() {
    tx = null;
    httpResponseReceived = false;
  }

  private boolean isInactive() {
    return tx == null || tx.requestTimeout.isDone();
  }

  private void releasePendingRequestExpectingContinue() {
    if (tx != null) {
      tx.releasePendingRequestExpectingContinue();
    }
  }

  private void crash(ChannelHandlerContext ctx, Throwable cause, boolean close, HttpTx tx) {
    releasePendingRequestExpectingContinue();
    try {
      tx.requestTimeout.cancel();
      tx.listener.onThrowable(cause);
      setInactive();

    } catch (Exception e) {
      LOGGER.error(
          "Exception while handling HTTP/1.1 crash, please report to Gatling maintainers", e);
    } finally {
      if (close) {
        ctx.close();
      }
    }
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {

    HttpTx tx = (HttpTx) msg;
    setActive(tx);

    if (tx.requestTimeout.isDone()) {
      setInactive();
      return;
    }

    try {
      WritableRequest request =
          WritableRequestBuilder.buildRequest(tx.request, ctx.alloc(), false, tx.listener);
      LOGGER.debug("Write request {}", request);

      tx.listener.onWrite(ctx.channel());
      if (HttpUtil.is100ContinueExpected(request.getRequest())) {
        LOGGER.debug("Delaying body write");
        tx.pendingRequestExpectingContinue = request;
        request.writeWithoutContent(ctx);
      } else {
        request.write(ctx);
      }

    } catch (Exception e) {
      exceptionCaught(ctx, e);
    }
  }

  private boolean exitOnDecodingFailure(ChannelHandlerContext ctx, DecoderResultProvider message) {
    Throwable t = message.decoderResult().cause();
    if (t != null) {
      exceptionCaught(ctx, t);
      return true;
    }
    return false;
  }

  private void channelReadHttpResponse(ChannelHandlerContext ctx, HttpResponse response) {
    HttpResponseStatus status = response.status();

    if (tx.pendingRequestExpectingContinue != null) {
      if (status.equals(HttpResponseStatus.CONTINUE)) {
        LOGGER.debug("Received 100-Continue");
        return;

      } else {
        // TODO implement 417 support
        LOGGER.debug(
            "Request was sent with Expect:100-Continue but received response with status {}, dropping",
            status);
        tx.releasePendingRequestExpectingContinue();
      }
    }

    httpResponseReceived = true;
    if (exitOnDecodingFailure(ctx, response)) {
      return;
    }
    tx.listener.onHttpResponse(status, response.headers());
    tx.closeConnection = tx.closeConnection || HttpUtils.isConnectionClose(response.headers());
  }

  private void channelReadHttpContent(ChannelHandlerContext ctx, HttpContent chunk, boolean last) {
    if (exitOnDecodingFailure(ctx, chunk)) {
      return;
    }

    if (tx.pendingRequestExpectingContinue != null) {
      if (last) {
        LOGGER.debug("Received 100-Continue' LastHttpContent, sending body");
        tx.pendingRequestExpectingContinue.writeContent(ctx);
        tx.pendingRequestExpectingContinue = null;
      }
      return;
    }

    // making a local copy because setInactive might be called (on last)
    HttpTx tx = this.tx;

    if (last) {
      tx.requestTimeout.cancel();
      setInactive();
      if (tx.closeConnection) {
        ctx.channel().close();
      } else {
        channelPool.offer(ctx.channel());
      }
    }

    try {
      tx.listener.onHttpResponseBodyChunk(chunk.content(), last);
    } catch (Throwable e) {
      // can't let exceptionCaught handle this because setInactive might have been called (on
      // last)
      crash(ctx, e, true, tx);
      throw e;
    }
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (isInactive()) {
      return;
    }

    LOGGER.debug("Read msg='{}'", msg);

    if (msg instanceof DefaultHttpResponse) {
      // fast path
      channelReadHttpResponse(ctx, (DefaultHttpResponse) msg);

    } else if (msg == LastHttpContent.EMPTY_LAST_CONTENT) {
      // fast path
      channelReadHttpContent(ctx, LastHttpContent.EMPTY_LAST_CONTENT, true);

    } else if (msg instanceof DefaultLastHttpContent) {
      // fast path
      DefaultLastHttpContent chunk = (DefaultLastHttpContent) msg;
      try {
        channelReadHttpContent(ctx, chunk, true);
      } finally {
        chunk.release();
      }

    } else if (msg instanceof DefaultHttpContent) {
      // fast path
      DefaultHttpContent chunk = (DefaultHttpContent) msg;
      try {
        channelReadHttpContent(ctx, chunk, false);
      } finally {
        chunk.release();
      }

    } else if (msg instanceof HttpResponse) {
      // slow path
      try {
        channelReadHttpResponse(ctx, (HttpResponse) msg);
      } finally {
        ReferenceCountUtil.release(msg);
      }

    } else if (msg instanceof HttpContent) {
      // slow path
      try {
        channelReadHttpContent(ctx, (HttpContent) msg, msg instanceof LastHttpContent);
      } finally {
        ReferenceCountUtil.release(msg);
      }
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    releasePendingRequestExpectingContinue();

    if (isInactive()) {
      return;
    }

    HttpTx tx = this.tx;
    setInactive();
    tx.requestTimeout.cancel();

    // only retry when we haven't started receiving response
    if (!httpResponseReceived && client.canRetry(tx)) {
      client.retry(tx, ctx.channel().eventLoop());
    } else {
      crash(ctx, PREMATURE_CLOSE, false, tx);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    releasePendingRequestExpectingContinue();

    if (cause instanceof Error) {
      LOGGER.error("Fatal error", cause);
      System.exit(1);
    }

    if (isInactive()) {
      return;
    }
    crash(ctx, cause, true, tx);
  }
}
