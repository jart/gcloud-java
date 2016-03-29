/*
 * Copyright 2016 Google Inc. All Rights Reserved.
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

package com.google.gcloud.storage.contrib.nio;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.factory.AutoFactory;
import com.google.auto.factory.Provided;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

import javax.annotation.CheckReturnValue;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Google Cloud Storage {@link FileSystem} implementation.
 *
 * @see <a href="https://developers.google.com/storage/docs/concepts-techniques#concepts">
 *        Concepts and Terminology</a>
 * @see <a href="https://developers.google.com/storage/docs/bucketnaming">
 *        Bucket and Object Naming Guidelines</a>
 */
@ThreadSafe
public final class CloudStorageFileSystem extends FileSystem {

  private static final Logger logger = Logger.getLogger(CloudStorageFileSystem.class.getName());

  /**
   * Invokes {@link #forBucket(String, CloudStorageConfiguration)} with
   * {@link CloudStorageConfiguration#defaultInstance()}.
   */
  @CheckReturnValue
  public static CloudStorageFileSystem forBucket(String bucket) {
    return forBucket(bucket, CloudStorageConfiguration.defaultInstance());
  }

  /**
   * Returns Google Cloud Storage {@link FileSystem} object for {@code bucket}.
   *
   * <p>GCS file system objects are basically free. You can create as many as you want, even if you
   * have multiple instances for the same bucket. There's no actual system resources associated
   * with this object. Therefore calling {@link #close()} on the returned value is optional.
   *
   * <p><b>Note:</b> It is also possible to instantiate this class via Java's Service Provider
   * Interface (SPI), e.g. {@code FileSystems.getFileSystem(URI.create("gs://bucket"))}. We
   * discourage you from using the SPI if possible, for the reasons documented in
   * {@link CloudStorageFileSystemProvider#newFileSystem(URI, java.util.Map)}
   *
   * @see #forBucket(String, CloudStorageConfiguration)
   * @see java.nio.file.FileSystems#getFileSystem(java.net.URI)
   */
  @CheckReturnValue
  public static CloudStorageFileSystem forBucket(String bucket, CloudStorageConfiguration config) {
    checkNotNull(config);
    checkArgument(
        !bucket.startsWith(URI_SCHEME + ":"), "Bucket name must not have schema: %s", bucket);
    return new CloudStorageFileSystem(getProvider(), config, bucket);
  }

  private static CloudStorageFileSystemProvider getProvider() {
    // XXX: This is a kludge to get the provider instance from the SPI. This is necessary since
    //      the behavior of NIO changes quite a bit if the provider instances aren't the same.
    //      If the provider can not be found via the SPI, then we fall back to instantiating it
    //      ourselves. This should safeguard against situations where the weird provider file
    //      doesn't find its way into the jar.
    FileSystemProvider provider =
        Iterables.getOnlyElement(
            Iterables.filter(
                FileSystemProvider.installedProviders(),
                Predicates.instanceOf(CloudStorageFileSystemProvider.class)),
            null);
    if (provider != null) {
      return (CloudStorageFileSystemProvider) provider;
    }
    logger.warning("Could not find CloudStorageFileSystemProvider via the SPI");
    return new CloudStorageFileSystemProvider();
  }

  public static final String URI_SCHEME = "gs";
  public static final String GCS_VIEW = "gcs";
  public static final String BASIC_VIEW = "basic";
  public static final int BLOCK_SIZE_DEFAULT = 2 * 1024 * 1024;
  public static final FileTime FILE_TIME_UNKNOWN = FileTime.fromMillis(0);
  public static final ImmutableSet<String> SUPPORTED_VIEWS = ImmutableSet.of(BASIC_VIEW, GCS_VIEW);

  private final String bucket;
  private final CloudStorageFileSystemProvider provider;
  private final CloudStorageConfiguration config;

  @AutoFactory
  CloudStorageFileSystem(
      @Provided CloudStorageFileSystemProvider provider,
      @Provided CloudStorageConfiguration config,
      String bucket) {
    checkArgument(!bucket.isEmpty(), "bucket");
    this.provider = provider;
    this.bucket = bucket;
    this.config = config;
  }

  @Override
  public CloudStorageFileSystemProvider provider() {
    return provider;
  }

  /**
   * Returns Cloud Storage bucket name being served by this file system.
   */
  public String bucket() {
    return bucket;
  }

  /**
   * Returns configuration object for this file system instance.
   */
  public CloudStorageConfiguration config() {
    return config;
  }

  /**
   * Converts Cloud Storage object name to a {@link Path} object.
   */
  @Override
  public CloudStoragePath getPath(String first, String... more) {
    checkArgument(
        !first.startsWith(URI_SCHEME + ":"),
        "GCS FileSystem.getPath() must not have schema and bucket name: %s",
        first);
    return CloudStoragePath.getPath(this, first, more);
  }

  /**
   * Does nothing currently. This method <i>might</i> be updated in the future to close all channels
   * associated with this file system object. However it's unlikely that even then, calling this
   * method will become mandatory.
   */
  @Override
  public void close() throws IOException {
    // TODO(#809): Synchronously close all channels associated with this FileSystem instance.
  }

  /**
   * Returns {@code true}, even if you previously called the {@link #close()} method.
   */
  @Override
  public boolean isOpen() {
    return true;
  }

  /**
   * Returns {@code false}.
   */
  @Override
  public boolean isReadOnly() {
    return false;
  }

  /**
   * Returns {@value UnixPath#SEPARATOR}.
   */
  @Override
  public String getSeparator() {
    return "" + UnixPath.SEPARATOR;
  }

  @Override
  public Iterable<Path> getRootDirectories() {
    return ImmutableSet.<Path>of(CloudStoragePath.getPath(this, UnixPath.ROOT));
  }

  /**
   * Returns nothing because GCS doesn't have disk partitions of limited size, or anything similar.
   */
  @Override
  public Iterable<FileStore> getFileStores() {
    return ImmutableSet.of();
  }

  @Override
  public Set<String> supportedFileAttributeViews() {
    return SUPPORTED_VIEWS;
  }

  /**
   * Throws {@link UnsupportedOperationException} because this feature hasn't been implemented yet.
   */
  @Override
  public PathMatcher getPathMatcher(String syntaxAndPattern) {
    // TODO(#813): Implement me.
    throw new UnsupportedOperationException();
  }

  /**
   * Throws {@link UnsupportedOperationException} because this feature hasn't been implemented yet.
   */
  @Override
  public UserPrincipalLookupService getUserPrincipalLookupService() {
    // TODO: Implement me.
    throw new UnsupportedOperationException();
  }

  /**
   * Throws {@link UnsupportedOperationException} because this feature hasn't been implemented yet.
   */
  @Override
  public WatchService newWatchService() throws IOException {
    // TODO: Implement me.
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof CloudStorageFileSystem
            && Objects.equals(config, ((CloudStorageFileSystem) other).config)
            && Objects.equals(bucket, ((CloudStorageFileSystem) other).bucket);
  }

  @Override
  public int hashCode() {
    return Objects.hash(bucket);
  }

  @Override
  public String toString() {
    try {
      return new URI(URI_SCHEME, bucket, null, null).toString();
    } catch (URISyntaxException e) {
      throw new AssertionError(e);
    }
  }
}