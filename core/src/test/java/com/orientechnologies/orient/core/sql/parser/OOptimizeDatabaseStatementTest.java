package com.orientechnologies.orient.core.sql.parser;

import org.testng.annotations.Test;

@Test
public class OOptimizeDatabaseStatementTest extends OParserTestAbstract {

  @Test
  public void testPlain() {

    checkRightSyntax("OPTIMIZE DATABASE");
    checkRightSyntax("optimize database");
    checkRightSyntax("OPTIMIZE DATABASE -lwedges -noverbose");
    checkRightSyntax("OPTIMIZE DATABASE -lwedges");


  }

}
