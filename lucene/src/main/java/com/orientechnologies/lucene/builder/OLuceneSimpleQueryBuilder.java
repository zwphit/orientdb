/*
 *
 *  * Copyright 2014 Orient Technologies.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *  
 */

package com.orientechnologies.lucene.builder;

import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.parser.OStringParser;
import com.orientechnologies.orient.core.index.OIndexDefinition;
import com.orientechnologies.orient.core.sql.parser.ParseException;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.Query;

/**
 * Created by Enrico Risa on 02/09/15.
 */
public class OLuceneSimpleQueryBuilder implements OQueryBuilder {

  @Override
  public Query query(OIndexDefinition index, Object key, Analyzer analyzer) throws ParseException {
    OLogManager.instance().info(this, "key:: " + key);
    String query = key.toString().trim();
    query = query.replaceFirst("\"","");
    query = query.substring(0,query.lastIndexOf("\""));

    try {
      return new QueryParser("", analyzer).parse(query);
    } catch (org.apache.lucene.queryparser.classic.ParseException e) {
      throw new ParseException(e.getMessage());
    }
  }

}

