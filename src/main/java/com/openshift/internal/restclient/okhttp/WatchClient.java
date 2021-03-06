/******************************************************************************* 
 * Copyright (c) 2016 Red Hat, Inc. 
 * Distributed under license by Red Hat, Inc. All rights reserved. 
 * This program is made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, 
 * and is available at http://www.eclipse.org/legal/epl-v10.html 
 * 
 * Contributors: 
 * Red Hat, Inc. - initial API and implementation 
 ******************************************************************************/
package com.openshift.internal.restclient.okhttp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang.StringUtils;
import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openshift.internal.restclient.DefaultClient;
import com.openshift.internal.restclient.URLBuilder;
import com.openshift.internal.restclient.model.KubernetesResource;
import com.openshift.internal.restclient.model.properties.ResourcePropertyKeys;
import com.openshift.restclient.IApiTypeMapper;
import com.openshift.restclient.IOpenShiftWatchListener;
import com.openshift.restclient.IWatcher;
import com.openshift.restclient.OpenShiftException;
import com.openshift.restclient.IOpenShiftWatchListener.ChangeType;
import com.openshift.restclient.http.IHttpConstants;
import com.openshift.restclient.model.IList;
import com.openshift.restclient.model.IResource;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.ws.WebSocket;
import okhttp3.ws.WebSocketCall;
import okhttp3.ws.WebSocketListener;
import okio.Buffer;

public class WatchClient implements IWatcher, IHttpConstants {

	private static final Logger LOGGER = LoggerFactory.getLogger(WatchClient.class);
	private DefaultClient client;
	private OkHttpClient okClient;
	private static AtomicReference<Status> status = new AtomicReference<>(Status.Stopped);
	private WebSocket wsClient;
	private IApiTypeMapper typeMappings;
	
	private enum Status {
		Started,
		Starting,
		Stopped,
		Stopping
	}
	
	public WatchClient(DefaultClient client, IApiTypeMapper typeMapper, OkHttpClient okClient) {
		this.client = client;
		this.typeMappings = typeMapper;
		this.okClient = okClient;
	}
	
	@Override
	public void stop() {
		if(status.get() == Status.Stopping
				|| status.get() == Status.Stopped) {
			return;
		}
		try {
			if(wsClient != null) {
				wsClient.close(1000, "Client was asked to stop.");
				wsClient = null;
				status.set(Status.Stopped);
			}
		} catch (Exception e) {
			LOGGER.debug("Unable to stop the watch client",e);
		}	
	}
	
	public IWatcher watch(Collection<String> kinds, String namespace, IOpenShiftWatchListener listener) {
		
		try {
			for (String kind : kinds) {
				WatchEndpoint socket = new WatchEndpoint(listener, kind);
				final String resourceVersion = getResourceVersion(kind, namespace, socket);
				
				final String endpoint = new URLBuilder(client.getBaseURL(), this.typeMappings)
						.kind(kind)
						.namespace(namespace)
						.watch()
						.addParmeter(ResourcePropertyKeys.RESOURCE_VERSION, resourceVersion)
						.websocket();
				Request request = client.newRequestBuilderTo(endpoint)
						.header(PROPERTY_ORIGIN, client.getBaseURL().toString())
						.header(PROPERTY_USER_AGENT, "openshift-restclient-java")
						.build();	
				WebSocketCall call = WebSocketCall.create(okClient, request);
				call.enqueue(socket);
			}
		} catch (Exception e) {
			try {
				throw ResponseCodeInterceptor.createOpenShiftException(client, 500, String.format("Could not watch resources in namespace %s: %s", namespace, e.getMessage()), null, e);
			} catch (IOException e1) {
				throw new OpenShiftException(e1, "IOException trying to create an OpenShift specific exception");
			}
		}
		return this;
	}
	
	private String getResourceVersion(String kind, String namespace, WatchEndpoint endpoint) throws Exception{
		IList list = client.get(kind, namespace);
		Collection<IResource> items = list.getItems();
		List<IResource> resources = new ArrayList<>(items.size());
		resources.addAll(items);
		endpoint.setResources(resources);
		return list.getResourceVersion();
	}
	
	private class WatchEndpoint implements WebSocketListener{
		
		private IOpenShiftWatchListener listener;
		private List<IResource> resources;
		private final String kind;
		
		public WatchEndpoint(IOpenShiftWatchListener listener, String kind) {
			this.listener = listener;
			this.kind = kind;
		}
		
		public void setResources(List<IResource> resources) {
			this.resources = resources;
		}
		
		@Override
		public void onClose(int statusCode, String reason) {
			LOGGER.debug("WatchSocket closed for kind: {}, code: {}, reason: {}", new Object[]{kind, statusCode, reason});
			listener.disconnected();
			status.set(Status.Stopped);
		}

		@Override
		public void onFailure(IOException err, Response response) {
			LOGGER.debug("WatchSocket Error for kind " + kind, err);
			try {
				if(response == null) {
					listener.error(ResponseCodeInterceptor.createOpenShiftException(client, 0, "", "", err));
				}else {
					listener.error(ResponseCodeInterceptor.createOpenShiftException(client, response.code(), response.body().string(), response.request().url().toString(), err));
				}
			} catch (IOException e) {
				LOGGER.error("IOException trying to notify listener of specific OpenShiftException", err);
				listener.error(err);
			}
		}

		@Override
		public void onMessage(ResponseBody body) throws IOException {
			String message = body.string();
			LOGGER.debug(message);
			KubernetesResource payload = client.getResourceFactory().create(message);
			ModelNode node = payload.getNode();
			IOpenShiftWatchListener.ChangeType event = new ChangeType(node.get("type").asString());
			IResource resource = client.getResourceFactory().create(node.get("object").toJSONString(true));
			if(StringUtils.isEmpty(resource.getKind())) {
				LOGGER.error("Unable to determine resource kind from: " + node.get("object").toJSONString(false));
			}
			listener.received(resource, event);
		}

		@Override
		public void onOpen(WebSocket socket, Response response) {
			LOGGER.debug("WatchSocket connected for {}", kind);
			status.set(Status.Started);
			wsClient = socket;
			listener.connected(resources);
		}

		@Override
		public void onPong(Buffer buffer) {
		}
		
	}

}
