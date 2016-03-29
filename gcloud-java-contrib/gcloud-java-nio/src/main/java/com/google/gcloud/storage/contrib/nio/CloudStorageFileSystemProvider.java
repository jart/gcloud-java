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
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.gcloud.storage.contrib.nio.CloudStorageFileSystem.URI_SCHEME;
import static com.google.gcloud.storage.contrib.nio.CloudStorageUtil.checkBucket;
import static com.google.gcloud.storage.contrib.nio.CloudStorageUtil.checkNotNullArray;
import static com.google.gcloud.storage.contrib.nio.CloudStorageUtil.checkPath;
import static com.google.gcloud.storage.contrib.nio.CloudStorageUtil.stripPathFromUri;

import com.google.auto.service.AutoService;
import com.google.common.base.MoreObjects;
import com.google.common.primitives.Ints;
import com.google.gcloud.storage.Acl;
import com.google.gcloud.storage.BlobId;
import com.google.gcloud.storage.BlobInfo;
import com.google.gcloud.storage.CopyWriter;
import com.google.gcloud.storage.Storage;
import com.google.gcloud.storage.StorageException;
import com.google.gcloud.storage.StorageOptions;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Google Cloud Storage {@link FileSystemProvider} implementation.
 */
@Singleton
@ThreadSafe
@AutoService(FileSystemProvider.class)
public final class CloudStorageFileSystemProvider extends FileSystemProvider {

  private final Storage storage;

  @Inject
  CloudStorageFileSystemProvider(Storage storage) {
    this.storage = storage;
  }

  /**
   * Constructs a new instance with the default options.
   *
   * <p><b>Note:</b> This should <i>only</i> be called by the Java SPI. Please use
   * {@link CloudStorageFileSystem#forBucket(String, CloudStorageConfiguration)} instead.
   */
  public CloudStorageFileSystemProvider() {
    this(StorageOptions.defaultInstance().service());
  }

  @Override
  public String getScheme() {
    return URI_SCHEME;
  }

  /**
   * Calls {@link #newFileSystem(URI, Map)} with an empty configuration map.
   */
  @Override
  public CloudStorageFileSystem getFileSystem(URI uri) {
    return newFileSystem(uri, Collections.<String, Object>emptyMap());
  }

  /**
   * Returns Cloud Storage file system, provided a URI with no path.
   *
   * <p><b>Note:</b> This method should be invoked indirectly via the SPI by calling
   * {@link java.nio.file.FileSystems#newFileSystem(URI, Map) FileSystems.newFileSystem()}; however,
   * we recommend that you don't use the API if possible. The recommended approach is to write a
   * dependency injection module that calls the statically-linked, type-safe version of this method:
   * {@link CloudStorageFileSystem#forBucket(String, CloudStorageConfiguration)}. Please see that
   * method for further documentation on creating GCS file systems.
   *
   * @param uri bucket and current working directory, e.g. {@code gs://bucket}
   * @param env map of configuration options, whose keys correspond to the method names of
   *     {@link CloudStorageConfiguration.Builder}. However you are not allowed to set the working
   *     directory, as that should be provided in the {@code uri}
   * @throws IllegalArgumentException if {@code uri} specifies a user, query, fragment, or scheme is
   *     not {@value CloudStorageFileSystem#URI_SCHEME}
   */
  @Override
  public CloudStorageFileSystem newFileSystem(URI uri, Map<String, ?> env) {
    checkArgument(
        uri.getScheme().equalsIgnoreCase(URI_SCHEME),
        "Cloud Storage URIs must have '%s' scheme: %s",
        URI_SCHEME,
        uri);
    checkArgument(
        !isNullOrEmpty(uri.getHost()), "%s:// URIs must have a host: %s", URI_SCHEME, uri);
    checkArgument(
        uri.getPort() == -1
            && isNullOrEmpty(uri.getQuery())
            && isNullOrEmpty(uri.getFragment())
            && isNullOrEmpty(uri.getUserInfo()),
        "GCS FileSystem URIs mustn't have: port, userinfo, path, query, or fragment: %s",
        uri);
    checkBucket(uri.getHost());
    return new CloudStorageFileSystem(
        this, CloudStorageConfiguration.fromMap(uri.getPath(), env), uri.getHost());
  }

  @Override
  public CloudStoragePath getPath(URI uri) {
    return CloudStoragePath.getPath(getFileSystem(stripPathFromUri(uri)), uri.getPath());
  }

  @Override
  public SeekableByteChannel newByteChannel(
      Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    checkNotNull(path);
    checkNotNullArray(attrs);
    if (options.contains(StandardOpenOption.WRITE)) {
      // TODO: Make our OpenOptions implement FileAttribute. Also remove buffer option.
      return newWriteChannel(path, options);
    } else {
      return newReadChannel(path, options);
    }
  }

  private SeekableByteChannel newReadChannel(Path path, Set<? extends OpenOption> options)
      throws IOException {
    for (OpenOption option : options) {
      if (option instanceof StandardOpenOption) {
        switch ((StandardOpenOption) option) {
          case READ:
            // Default behavior.
            break;
          case SPARSE:
          case TRUNCATE_EXISTING:
            // Ignored by specification.
            break;
          case WRITE:
            throw new IllegalArgumentException("READ+WRITE not supported yet");
          case APPEND:
          case CREATE:
          case CREATE_NEW:
          case DELETE_ON_CLOSE:
          case DSYNC:
          case SYNC:
          default:
            throw new UnsupportedOperationException(option.toString());
        }
      } else {
        throw new UnsupportedOperationException(option.toString());
      }
    }
    CloudStoragePath cloudPath = checkPath(path);
    if (cloudPath.seemsLikeADirectoryAndUsePseudoDirectories()) {
      throw new CloudStoragePseudoDirectoryException(cloudPath);
    }
    return CloudStorageReadChannel.create(storage, cloudPath.getBlobId(), 0);
  }

  private SeekableByteChannel newWriteChannel(Path path, Set<? extends OpenOption> options)
      throws IOException {

    CloudStoragePath cloudPath = checkPath(path);
    if (cloudPath.seemsLikeADirectoryAndUsePseudoDirectories()) {
      throw new CloudStoragePseudoDirectoryException(cloudPath);
    }
    BlobId file = cloudPath.getBlobId();
    BlobInfo.Builder infoBuilder = BlobInfo.builder(file);
    List<Storage.BlobWriteOption> writeOptions = new ArrayList<>();
    List<Acl> acls = new ArrayList<>();

    HashMap<String, String> metas = new HashMap<>();
    for (OpenOption option : options) {
      if (option instanceof OptionMimeType) {
        infoBuilder.contentType(((OptionMimeType) option).mimeType());
      } else if (option instanceof OptionCacheControl) {
        infoBuilder.cacheControl(((OptionCacheControl) option).cacheControl());
      } else if (option instanceof OptionContentDisposition) {
        infoBuilder.contentDisposition(((OptionContentDisposition) option).contentDisposition());
      } else if (option instanceof OptionContentEncoding) {
        infoBuilder.contentEncoding(((OptionContentEncoding) option).contentEncoding());
      } else if (option instanceof OptionUserMetadata) {
        OptionUserMetadata opMeta = (OptionUserMetadata) option;
        metas.put(opMeta.key(), opMeta.value());
      } else if (option instanceof OptionAcl) {
        acls.add(((OptionAcl) option).acl());
      } else if (option instanceof OptionBlockSize) {
        // TODO: figure out how to plumb in block size.
      } else if (option instanceof StandardOpenOption) {
        switch ((StandardOpenOption) option) {
          case CREATE:
          case TRUNCATE_EXISTING:
          case WRITE:
            // Default behavior.
            break;
          case SPARSE:
            // Ignored by specification.
            break;
          case CREATE_NEW:
            writeOptions.add(Storage.BlobWriteOption.doesNotExist());
            break;
          case READ:
            throw new IllegalArgumentException("READ+WRITE not supported yet");
          case APPEND:
          case DELETE_ON_CLOSE:
          case DSYNC:
          case SYNC:
          default:
            throw new UnsupportedOperationException(option.toString());
        }
      } else if (option instanceof CloudStorageOption) {
        // XXX: We need to interpret these later
      } else {
        throw new UnsupportedOperationException(option.toString());
      }
    }

    if (!metas.isEmpty()) {
      infoBuilder.metadata(metas);
    }
    if (!acls.isEmpty()) {
      infoBuilder.acl(acls);
    }

    try {
      return new CloudStorageWriteChannel(
          storage.writer(
              infoBuilder.build(), writeOptions.toArray(new Storage.BlobWriteOption[0])));
    } catch (StorageException oops) {
      throw asIOException(oops);
    }
  }

  @Override
  public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
    InputStream result = super.newInputStream(path, options);
    CloudStoragePath cloudPath = checkPath(path);
    int blockSize = cloudPath.getFileSystem().config().blockSize();
    for (OpenOption option : options) {
      if (option instanceof OptionBlockSize) {
        blockSize = ((OptionBlockSize) option).size();
      }
    }
    return new BufferedInputStream(result, blockSize);
  }

  @Override
  public boolean deleteIfExists(Path path) throws IOException {
    CloudStoragePath cloudPath = checkPath(path);
    if (cloudPath.seemsLikeADirectoryAndUsePseudoDirectories()) {
      throw new CloudStoragePseudoDirectoryException(cloudPath);
    }
    return storage.delete(cloudPath.getBlobId());
  }

  @Override
  public void delete(Path path) throws IOException {
    CloudStoragePath cloudPath = checkPath(path);
    if (!deleteIfExists(cloudPath)) {
      throw new NoSuchFileException(cloudPath.toString());
    }
  }

  @Override
  public void move(Path source, Path target, CopyOption... options) throws IOException {
    for (CopyOption option : options) {
      if (option == StandardCopyOption.ATOMIC_MOVE) {
        throw new AtomicMoveNotSupportedException(
            source.toString(),
            target.toString(),
            "Google Cloud Storage does not support atomic move operations.");
      }
    }
    copy(source, target, options);
    delete(source);
  }

  @Override
  public void copy(Path source, Path target, CopyOption... options) throws IOException {
    boolean wantCopyAttributes = false;
    boolean wantReplaceExisting = false;
    boolean setContentType = false;
    boolean setCacheControl = false;
    boolean setContentEncoding = false;
    boolean setContentDisposition = false;

    CloudStoragePath toPath = checkPath(target);
    BlobInfo.Builder tgtInfoBuilder = BlobInfo.builder(toPath.getBlobId()).contentType("");

    int blockSize = -1;
    for (CopyOption option : options) {
      if (option instanceof StandardCopyOption) {
        switch ((StandardCopyOption) option) {
          case COPY_ATTRIBUTES:
            wantCopyAttributes = true;
            break;
          case REPLACE_EXISTING:
            wantReplaceExisting = true;
            break;
          case ATOMIC_MOVE:
          default:
            throw new UnsupportedOperationException(option.toString());
        }
      } else if (option instanceof CloudStorageOption) {
        if (option instanceof OptionBlockSize) {
          blockSize = ((OptionBlockSize) option).size();
        } else if (option instanceof OptionMimeType) {
          tgtInfoBuilder.contentType(((OptionMimeType) option).mimeType());
          setContentType = true;
        } else if (option instanceof OptionCacheControl) {
          tgtInfoBuilder.cacheControl(((OptionCacheControl) option).cacheControl());
          setCacheControl = true;
        } else if (option instanceof OptionContentEncoding) {
          tgtInfoBuilder.contentEncoding(((OptionContentEncoding) option).contentEncoding());
          setContentEncoding = true;
        } else if (option instanceof OptionContentDisposition) {
          tgtInfoBuilder.contentDisposition(
              ((OptionContentDisposition) option).contentDisposition());
          setContentDisposition = true;
        } else {
          throw new UnsupportedOperationException(option.toString());
        }
      } else {
        throw new UnsupportedOperationException(option.toString());
      }
    }

    CloudStoragePath fromPath = checkPath(source);

    blockSize =
        blockSize != -1
            ? blockSize
            : Ints.max(
                fromPath.getFileSystem().config().blockSize(),
                toPath.getFileSystem().config().blockSize());
    // TODO: actually use blockSize

    if (fromPath.seemsLikeADirectory() && toPath.seemsLikeADirectory()) {
      if (fromPath.getFileSystem().config().usePseudoDirectories()
          && toPath.getFileSystem().config().usePseudoDirectories()) {
        // NOOP: This would normally create an empty directory.
        return;
      } else {
        checkArgument(
            !fromPath.getFileSystem().config().usePseudoDirectories()
                && !toPath.getFileSystem().config().usePseudoDirectories(),
            "File systems associated with paths don't agree on pseudo-directories.");
      }
    }
    if (fromPath.seemsLikeADirectoryAndUsePseudoDirectories()) {
      throw new CloudStoragePseudoDirectoryException(fromPath);
    }
    if (toPath.seemsLikeADirectoryAndUsePseudoDirectories()) {
      throw new CloudStoragePseudoDirectoryException(toPath);
    }

    try {
      if (wantCopyAttributes) {
        BlobInfo blobInfo = storage.get(fromPath.getBlobId());
        if (null == blobInfo) {
          throw new NoSuchFileException(fromPath.toString());
        }
        if (!setCacheControl) {
          tgtInfoBuilder.cacheControl(blobInfo.cacheControl());
        }
        if (!setContentType) {
          tgtInfoBuilder.contentType(blobInfo.contentType());
        }
        if (!setContentEncoding) {
          tgtInfoBuilder.contentEncoding(blobInfo.contentEncoding());
        }
        if (!setContentDisposition) {
          tgtInfoBuilder.contentDisposition(blobInfo.contentDisposition());
        }
        tgtInfoBuilder.acl(blobInfo.acl());
        tgtInfoBuilder.metadata(blobInfo.metadata());
      }

      BlobInfo tgtInfo = tgtInfoBuilder.build();
      Storage.CopyRequest.Builder copyReqBuilder =
          Storage.CopyRequest.builder().source(fromPath.getBlobId());
      if (wantReplaceExisting) {
        copyReqBuilder = copyReqBuilder.target(tgtInfo);
      } else {
        copyReqBuilder = copyReqBuilder.target(tgtInfo, Storage.BlobTargetOption.doesNotExist());
      }
      CopyWriter copyWriter = storage.copy(copyReqBuilder.build());
      copyWriter.result();
    } catch (StorageException oops) {
      throw asIOException(oops);
    }
  }

  @Override
  public boolean isSameFile(Path path, Path path2) {
    return checkPath(path).equals(checkPath(path2));
  }

  /**
   * Always returns {@code false}, because GCS doesn't support hidden files.
   */
  @Override
  public boolean isHidden(Path path) {
    checkPath(path);
    return false;
  }

  @Override
  public void checkAccess(Path path, AccessMode... modes) throws IOException {
    for (AccessMode mode : modes) {
      switch (mode) {
        case READ:
        case WRITE:
          break;
        case EXECUTE:
        default:
          throw new UnsupportedOperationException(mode.toString());
      }
    }
    CloudStoragePath cloudPath = checkPath(path);
    if (cloudPath.seemsLikeADirectoryAndUsePseudoDirectories()) {
      return;
    }
    if (storage.get(cloudPath.getBlobId(), Storage.BlobGetOption.fields(Storage.BlobField.ID))
        == null) {
      throw new NoSuchFileException(path.toString());
    }
  }

  @Override
  public <A extends BasicFileAttributes> A readAttributes(
      Path path, Class<A> type, LinkOption... options) throws IOException {
    checkNotNull(type);
    checkNotNullArray(options);
    if (type != CloudStorageFileAttributes.class && type != BasicFileAttributes.class) {
      throw new UnsupportedOperationException(type.getSimpleName());
    }
    CloudStoragePath cloudPath = checkPath(path);
    if (cloudPath.seemsLikeADirectoryAndUsePseudoDirectories()) {
      @SuppressWarnings("unchecked")
      A result = (A) new CloudStoragePseudoDirectoryAttributes(cloudPath);
      return result;
    }
    BlobInfo blobInfo = storage.get(cloudPath.getBlobId());
    // null size indicate a file that we haven't closed yet, so GCS treats it as not there yet.
    if (null == blobInfo || blobInfo.size() == null) {
      throw new NoSuchFileException(
          cloudPath.getBlobId().bucket() + "/" + cloudPath.getBlobId().name());
    }
    CloudStorageObjectAttributes ret;
    ret = new CloudStorageObjectAttributes(blobInfo);
    @SuppressWarnings("unchecked")
    A result = (A) ret;
    return result;
  }

  @Override
  public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) {
    // TODO(#811): Java 7 NIO defines at least eleven string attributes we'd want to support (eg.
    //             BasicFileAttributeView and PosixFileAttributeView), so rather than a partial
    //             implementation we rely on the other overload for now.
    throw new UnsupportedOperationException();
  }

  @Override
  public <V extends FileAttributeView> V getFileAttributeView(
      Path path, Class<V> type, LinkOption... options) {
    checkNotNull(type);
    checkNotNullArray(options);
    if (type != CloudStorageFileAttributeView.class && type != BasicFileAttributeView.class) {
      throw new UnsupportedOperationException(type.getSimpleName());
    }
    CloudStoragePath cloudPath = checkPath(path);
    @SuppressWarnings("unchecked")
    V result = (V) new CloudStorageFileAttributeView(storage, cloudPath);
    return result;
  }

  /**
   * Does nothing since GCS uses fake directories.
   */
  @Override
  public void createDirectory(Path dir, FileAttribute<?>... attrs) {
    checkPath(dir);
    checkNotNullArray(attrs);
  }

  /**
   * Throws {@link UnsupportedOperationException} because this feature hasn't been implemented yet.
   */
  @Override
  public DirectoryStream<Path> newDirectoryStream(Path dir, Filter<? super Path> filter) {
    // TODO(#813): Implement me.
    throw new UnsupportedOperationException();
  }

  /**
   * Throws {@link UnsupportedOperationException} because Cloud Storage objects are immutable.
   */
  @Override
  public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
    // TODO(#811): Implement me.
    throw new CloudStorageObjectImmutableException();
  }

  /**
   * Throws {@link UnsupportedOperationException} because this feature hasn't been implemented yet.
   */
  @Override
  public FileStore getFileStore(Path path) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof CloudStorageFileSystemProvider
            && Objects.equals(storage, ((CloudStorageFileSystemProvider) other).storage);
  }

  @Override
  public int hashCode() {
    return Objects.hash(storage);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("storage", storage).toString();
  }

  private IOException asIOException(StorageException oops) {
    // RPC API can only throw StorageException, but CloudStorageFileSystemProvider
    // can only throw IOException. Square peg, round hole.
    // TODO(#810): Research if other codes should be translated similarly.
    if (oops.code() == 404) {
      return new NoSuchFileException(oops.reason());
    }
    Throwable cause = oops.getCause();
    if (cause instanceof FileAlreadyExistsException) {
      return (FileAlreadyExistsException) cause;
    }
    return new IOException("Storage operation failed", oops);
  }
}