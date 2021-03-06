/*
 * Copyright 2014 Orient Technologies.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.orientechnologies.lucene.engine;

import com.orientechnologies.common.concur.resource.OSharedResourceAdaptiveExternal;
import com.orientechnologies.common.io.OFileUtils;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.serialization.types.OBinarySerializer;
import com.orientechnologies.lucene.analyzer.OLuceneAnalyzerFactory;
import com.orientechnologies.lucene.analyzer.OLucenePerFieldAnalyzerWrapper;
import com.orientechnologies.lucene.builder.OLuceneDocumentBuilder;
import com.orientechnologies.lucene.builder.OLuceneQueryBuilder;
import com.orientechnologies.lucene.tx.OLuceneTxChanges;
import com.orientechnologies.orient.core.OOrientListener;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.ODatabaseDocumentInternal;
import com.orientechnologies.orient.core.db.ODatabaseInternal;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.index.OIndexCursor;
import com.orientechnologies.orient.core.index.OIndexEngine.ValuesTransformer;
import com.orientechnologies.orient.core.index.OIndexKeyCursor;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.OLocalPaginatedStorage;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TrackingIndexWriter;
import org.apache.lucene.search.ControlledRealTimeReopenThread;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.RAMDirectory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.orientechnologies.lucene.engine.OLuceneIndexEngineAbstract.*;

public class OLuceneStorage extends OSharedResourceAdaptiveExternal implements OOrientListener {

  private final String              name;
  private final ODocument           metadata;
  protected     OLuceneFacetManager facetManager;
  protected     TimerTask           commitTask;
  protected AtomicBoolean closed = new AtomicBoolean(true);
  protected TrackingIndexWriter                   mgrWriter;
  protected SearcherManager                       searcherManager;
  protected ControlledRealTimeReopenThread        nrt;
  private   OLuceneDocumentBuilder                builder;
  private   OLuceneQueryBuilder                   queryBuilder;
  private   long                                  reopenToken;

  private Analyzer indexAnalyzer;
  private Analyzer queryAnalyzer;

  public OLuceneStorage(String name, OLuceneDocumentBuilder builder, OLuceneQueryBuilder queryBuilder, ODocument metadata) {
    super(OGlobalConfiguration.ENVIRONMENT_CONCURRENT.getValueAsBoolean(),
        OGlobalConfiguration.MVRBTREE_TIMEOUT.getValueAsInteger(), true);
    this.name = name;
    this.builder = builder;
    this.queryBuilder = queryBuilder;
    this.metadata = metadata;

    indexAnalyzer = new OLucenePerFieldAnalyzerWrapper(new StandardAnalyzer());
    queryAnalyzer = new OLucenePerFieldAnalyzerWrapper(new StandardAnalyzer());

    try {

      reOpen();

    } catch (IOException e) {
      OLogManager.instance().error(this, "Error on initializing Lucene index", e);
    }

    Orient.instance().registerListener(this);

    commitTask = new TimerTask() {
      @Override
      public void run() {
        if (Boolean.FALSE.equals(closed.get())) {
          commit();
        }
      }
    };
    Orient.instance().scheduleTask(commitTask, 10000, 10000);

    OLogManager.instance().info(this, "Index storage CREATED, timerTASK scheduled");

  }

  private void reOpen() throws IOException {

    if (mgrWriter != null) {
      OLogManager.instance().info(this, "index storage is open don't reopen");

      return;
    }
    ODatabaseDocumentInternal database = ODatabaseRecordThreadLocal.INSTANCE.get();

    final OAbstractPaginatedStorage storageLocalAbstract = (OAbstractPaginatedStorage) database.getStorage().getUnderlying();
    Directory dir = null;
    if (storageLocalAbstract instanceof OLocalPaginatedStorage) {
      String pathname = getIndexPath((OLocalPaginatedStorage) storageLocalAbstract);

      OLogManager.instance().info(this, "Opening NIOFS Lucene db=%s, path=%s", database.getName(), pathname);

      dir = NIOFSDirectory.open(new File(pathname).toPath());
    } else {

      OLogManager.instance().info(this, "Opening RAM Lucene index db=%s", database.getName());
      dir = new RAMDirectory();

    }

    final IndexWriter indexWriter = createIndexWriter(dir);

    mgrWriter = new TrackingIndexWriter(indexWriter);
    searcherManager = new SearcherManager(indexWriter, true, null);

    if (nrt != null) {
      nrt.close();
    }

    nrt = new ControlledRealTimeReopenThread(mgrWriter, searcherManager, 60.00, 0.1);
    nrt.setDaemon(true);
    nrt.start();
    flush();

    OLogManager.instance().info(this, "REOPEN DONE");
  }

  public void commit() {
    try {
      OLogManager.instance().info(this, "committing");
      final IndexWriter indexWriter = mgrWriter.getIndexWriter();
      indexWriter.forceMergeDeletes();
      indexWriter.commit();
    } catch (IOException e) {
      OLogManager.instance().error(this, "Error on committing Lucene index", e);
    }
  }

  private String getIndexPath(OLocalPaginatedStorage storageLocalAbstract) {
    return getIndexPath(storageLocalAbstract, "databaseIndex");
  }

  public IndexWriter createIndexWriter(Directory directory) throws IOException {

    OLuceneIndexWriterFactory fc = new OLuceneIndexWriterFactory();
    // TODO: manage taxo
    // facetManager = new OLuceneFacetManager(this, metadata);

    OLogManager.instance().debug(this, "Creating Lucene index in '%s'...", directory);
    return fc.createIndexWriter(directory, metadata, indexAnalyzer());
  }

  public void flush() {
    commit();

  }

  private String getIndexPath(OLocalPaginatedStorage storageLocalAbstract, String indexName) {
    return storageLocalAbstract.getStoragePath() + File.separator + OLUCENE_BASE_DIR + File.separator + indexName;
  }

  public Analyzer indexAnalyzer() {
    return indexAnalyzer;
  }

  public void initIndex(OLuceneClassIndexContext indexContext) {

    OLogManager.instance().info(this, "START INIT initIndex:: name " + indexContext.name + " def :: " + indexContext.definition);

    //    initializerAnalyzers(indexContext.indexClass, indexContext.metadata);

    OLuceneAnalyzerFactory afc = new OLuceneAnalyzerFactory();

    indexContext.metadata.field("prefix_with_class_name", true, OType.BOOLEAN);

    indexAnalyzer = afc.createAnalyzer(indexContext.definition, OLuceneAnalyzerFactory.AnalyzerKind.INDEX, indexContext.metadata);
    queryAnalyzer = afc.createAnalyzer(indexContext.definition, OLuceneAnalyzerFactory.AnalyzerKind.QUERY, indexContext.metadata);

    OLogManager.instance()
        .info(this, "DONE INIT initIndex:: indexAnalyzer::  " + indexAnalyzer + " queryanalzer:: " + queryAnalyzer);

  }

  public boolean remove(Object key, OIdentifiable value) {
    return false;
  }

  public long size() {

    try {
      return searcher().getIndexReader().numDocs();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return mgrWriter.getIndexWriter().maxDoc();
  }

  public IndexSearcher searcher() throws IOException {
    try {
      nrt.waitForGeneration(reopenToken);
      return searcherManager.acquire();
    } catch (InterruptedException e) {
      OLogManager.instance().error(this, "Error on get searcher from Lucene index", e);
    }
    return null;

  }

  public OLuceneTxChanges buildTxChanges() throws IOException {

    throw new RuntimeException("DON'T CALL ME");

  }

  public Query deleteQuery(String indexName, Object key, OIdentifiable value) {

    OLogManager.instance().info(this, "delete with query in index::  " + indexName);
    return null;
  }

  public void init() {
    OLogManager.instance().info(this, "INIT");

  }

  public void create(OBinarySerializer valueSerializer, boolean isAutomatic, OType[] keyTypes, boolean nullPointerSupport,
      OBinarySerializer keySerializer, int keySize) {

    OLogManager.instance().info(this, "CREATE:: ");

  }

  public void delete(String indexName) {
    OLogManager.instance().info(this, "DELETING:: " + indexName);

  }

  public void delete(final ODatabaseInternal database) {
    OLogManager.instance().info(this, "DELETING STORAGE:: ");

    close();

    final OAbstractPaginatedStorage storageLocalAbstract = (OAbstractPaginatedStorage) database.getStorage().getUnderlying();
    if (storageLocalAbstract instanceof OLocalPaginatedStorage) {
      String pathname = getIndexPath((OLocalPaginatedStorage) storageLocalAbstract);

      OFileUtils.deleteRecursively(new File(pathname));

    }
  }

  public void close() {
    OLogManager.instance().info(this, "CLOSING  engine");
    try {
      closeIndex();
    } catch (Throwable e) {
      OLogManager.instance().error(this, "Error on closing Lucene index", e);
    }

  }

  protected void closeIndex() throws IOException {
    OLogManager.instance().debug(this, "Closing Lucene engine'");

    if (nrt != null) {
      nrt.interrupt();
      nrt.close();
    }
    if (commitTask != null) {
      commitTask.cancel();
    }

    if (searcherManager != null)
      searcherManager.close();

    if (mgrWriter != null) {
      mgrWriter.getIndexWriter().forceMergeDeletes();
      mgrWriter.getIndexWriter().commit();
      mgrWriter.getIndexWriter().close();
    }
  }

  public void deleteWithoutLoad(String indexName) {
    OLogManager.instance().info(this, "DELETing withoutLoAD ::: " + indexName);

  }

  public void load(String indexName, OBinarySerializer valueSerializer, boolean isAutomatic, OBinarySerializer keySerializer,
      OType[] keyTypes, boolean nullPointerSupport, int keySize) {

    OLogManager.instance().info(this, "LOAD:: " + indexName);
  }

  public boolean contains(Object key) {
    return false;
  }

  public boolean remove(Object key) {
    return false;
  }

  public void clear(String indexName) {
    OLogManager.instance().info(this, "clear index:: " + indexName);
  }

  public void addDocument(Document doc) {
    try {
      OLogManager.instance().debug(this, "add document::  " + doc);
      final Term term = new Term(RID, doc.get(RID));
      reopenToken = mgrWriter.updateDocument(term, doc);
    } catch (IOException e) {
      OLogManager.instance().error(this, "Error on adding new document '%s' to Lucene index", e, doc);
    }
  }

  public Object getFirstKey() {
    return null;
  }

  public Object getLastKey() {
    return null;
  }

  public OIndexCursor iterateEntriesBetween(Object rangeFrom, boolean fromInclusive, Object rangeTo, boolean toInclusive,
      boolean ascSortOrder, ValuesTransformer transformer) {
    return null;
  }

  public OIndexCursor iterateEntriesMajor(Object fromKey, boolean isInclusive, boolean ascSortOrder,
      ValuesTransformer transformer) {
    return null;
  }

  public OIndexCursor iterateEntriesMinor(Object toKey, boolean isInclusive, boolean ascSortOrder, ValuesTransformer transformer) {
    return null;
  }

  public OIndexCursor cursor(ValuesTransformer valuesTransformer) {
    return null;
  }

  public OIndexCursor descCursor(ValuesTransformer valuesTransformer) {
    return null;
  }

  public OIndexKeyCursor keyCursor() {
    return null;
  }

  public long size(ValuesTransformer transformer) {
    return 0;
  }

  public boolean hasRangeQuerySupport() {
    return false;
  }

  public int getVersion() {
    return 0;
  }

  public String getName() {
    return name;
  }

  public Analyzer queryAnalyzer() {
    return queryAnalyzer;
  }

  @Override
  public void onShutdown() {
    OLogManager.instance().info(this, "ENGINE SHUTDONW");

    close();

  }

  @Override
  public void onStorageRegistered(OStorage storage) {

  }

  @Override
  public void onStorageUnregistered(OStorage storage) {

  }

}
