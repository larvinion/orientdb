/*
 *
 *  * Copyright 2010-2014 Orient Technologies LTD (info(at)orientechnologies.com)
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

package com.orientechnologies.orient.etl.transformer;

import com.orientechnologies.orient.core.command.OBasicCommandContext;
import com.orientechnologies.orient.core.command.OCommandRequest;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.OCommandSQL;
import com.orientechnologies.orient.etl.OETLProcessor;
import com.orientechnologies.orient.graph.gremlin.OCommandGremlin;

/**
 * Executes a command.
 */
public class OCommandTransformer extends OAbstractTransformer {
  protected String language = "sql";
  protected String command;

  @Override
  public ODocument getConfiguration() {
    return new ODocument().fromJSON("{parameters:[" + getCommonConfigurationParameters() + ","
        + "{language:{optional:true,description:'Command language, SQL by default'}},"
        + "{command:{optional:false,description:'Command to execute'}}]," + "input:['ODocument'],output:'ODocument'}");
  }

  @Override
  public void configure(final OETLProcessor iProcessor, final ODocument iConfiguration, final OBasicCommandContext iContext) {
    super.configure(iProcessor, iConfiguration, iContext);

    if (iConfiguration.containsField("language"))
      language = ((String) iConfiguration.field("language")).toLowerCase();
    command = (String) resolve(iConfiguration.field("command"));
  }

  @Override
  public String getName() {
    return "command";
  }

  @Override
  public Object executeTransform(final Object input) {

    OCommandRequest cmd = null;

    if (language.equals("sql")) {
      cmd = new OCommandSQL(command);
    } else if (language.equals("gremlin")) {
      cmd = new OCommandGremlin(command);
    } else
      throw new OTransformException(getName() + ": language '" + language + "' not supported");
    return pipeline.getDocumentDatabase().command(cmd).execute();
  }
}
