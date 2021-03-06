/*
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.gcloud.datastore;

import static com.google.gcloud.datastore.Validator.validateNamespace;

import com.google.api.services.datastore.DatastoreV1;
import com.google.api.services.datastore.DatastoreV1.EntityResult;
import com.google.api.services.datastore.DatastoreV1.LookupResponse;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.gcloud.ServiceOptions;
import com.google.gcloud.spi.DatastoreRpc;
import com.google.gcloud.spi.DatastoreRpcFactory;
import com.google.gcloud.spi.DefaultDatastoreRpc;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

public class DatastoreOptions extends ServiceOptions<Datastore, DatastoreRpc, DatastoreOptions> {

  private static final long serialVersionUID = 5056049000758143852L;
  private static final String DATASET_ENV_NAME = "DATASTORE_DATASET";
  private static final String HOST_ENV_NAME = "DATASTORE_HOST";
  private static final String DATASTORE_SCOPE = "https://www.googleapis.com/auth/datastore";
  private static final String USERINFO_SCOPE = "https://www.googleapis.com/auth/userinfo.email";
  private static final Set<String> SCOPES = ImmutableSet.of(DATASTORE_SCOPE, USERINFO_SCOPE);

  private final String namespace;
  private final boolean force;
  private final boolean normalizeDataset;

  public static class DefaultDatastoreFactory implements DatastoreFactory {

    private static final DatastoreFactory INSTANCE = new DefaultDatastoreFactory();

    @Override
    public Datastore create(DatastoreOptions options) {
      return new DatastoreImpl(options);
    }
  }

  public static class DefaultDatastoreRpcFactory implements DatastoreRpcFactory {

    private static final DatastoreRpcFactory INSTANCE = new DefaultDatastoreRpcFactory();

    @Override
    public DatastoreRpc create(DatastoreOptions options) {
      return new DefaultDatastoreRpc(options);
    }
  }

  public static class Builder extends
      ServiceOptions.Builder<Datastore, DatastoreRpc, DatastoreOptions, Builder> {

    private String namespace;
    private boolean force;
    private boolean normalizeDataset = true;

    private Builder() {
    }

    private Builder(DatastoreOptions options) {
      super(options);
      force = options.force;
      namespace = options.namespace;
      normalizeDataset = options.normalizeDataset;
    }

    @Override
    public DatastoreOptions build() {
      DatastoreOptions options = new DatastoreOptions(this);
      return normalizeDataset ? options.normalize() : options;
    }

    public Builder namespace(String namespace) {
      this.namespace = validateNamespace(namespace);
      return this;
    }

    public Builder force(boolean force) {
      this.force = force;
      return this;
    }

    Builder normalizeDataset(boolean normalizeDataset) {
      this.normalizeDataset = normalizeDataset;
      return this;
    }
  }

  private DatastoreOptions(Builder builder) {
    super(DatastoreFactory.class, DatastoreRpcFactory.class, builder);
    normalizeDataset = builder.normalizeDataset;
    namespace = builder.namespace != null ? builder.namespace : defaultNamespace();
    force = builder.force;
  }

  private DatastoreOptions normalize() {
    if (!normalizeDataset) {
      return this;
    }

    Builder builder = toBuilder();
    builder.normalizeDataset(false);
    // Replace provided project-id with full project-id (s~xxx, e~xxx,...)
    DatastoreV1.LookupRequest.Builder requestPb = DatastoreV1.LookupRequest.newBuilder();
    DatastoreV1.Key key = DatastoreV1.Key.newBuilder()
        .addPathElement(DatastoreV1.Key.PathElement.newBuilder().setKind("__foo__").setName("bar"))
        .build();
    requestPb.addKey(key);
    LookupResponse responsePb = rpc().lookup(requestPb.build());
    if (responsePb.getDeferredCount() > 0) {
      key = responsePb.getDeferred(0);
    } else {
      Iterator<EntityResult> combinedIter =
          Iterables.concat(responsePb.getMissingList(), responsePb.getFoundList()).iterator();
      key = combinedIter.next().getEntity().getKey();
    }
    builder.projectId(key.getPartitionId().getDatasetId());
    return new DatastoreOptions(builder);
  }

  @Override
  protected String defaultHost() {
    String host = System.getProperty(HOST_ENV_NAME, System.getenv(HOST_ENV_NAME));
    return host != null ? host : super.defaultHost();
  }

  @Override
  protected String defaultProject() {
    String projectId = System.getProperty(DATASET_ENV_NAME, System.getenv(DATASET_ENV_NAME));
    if (projectId == null) {
      projectId = appEngineAppId();
    }
    return projectId != null ? projectId : super.defaultProject();
  }

  @SuppressWarnings("unchecked")
  @Override
  protected DatastoreFactory defaultServiceFactory() {
    return DefaultDatastoreFactory.INSTANCE;
  }

  @SuppressWarnings("unchecked")
  @Override
  protected DatastoreRpcFactory defaultRpcFactory() {
    return DefaultDatastoreRpcFactory.INSTANCE;
  }

  public String namespace() {
    return namespace;
  }

  /**
   * Returns a default {@code DatastoreOptions} instance.
   */
  public static DatastoreOptions defaultInstance() {
    return builder().build();
  }

  private static String defaultNamespace() {
    try {
      Class<?> clazz = Class.forName("com.google.appengine.api.NamespaceManager");
      Method method = clazz.getMethod("get");
      String namespace = (String) method.invoke(null);
      return namespace == null || namespace.isEmpty() ? null : namespace;
    } catch (Exception ignore) {
      // return null (Datastore default namespace) if could not automatically determine
      return null;
    }
  }

  public boolean force() {
    return force;
  }

  @Override
  protected Set<String> scopes() {
    return SCOPES;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public int hashCode() {
    return baseHashCode() ^ Objects.hash(namespace, force, normalizeDataset);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof DatastoreOptions)) {
      return false;
    }
    DatastoreOptions other = (DatastoreOptions) obj;
    return baseEquals(other) && Objects.equals(namespace, other.namespace)
        && Objects.equals(force, other.force)
        && Objects.equals(normalizeDataset, other.normalizeDataset);
  }

  public static Builder builder() {
    return new Builder();
  }
}
