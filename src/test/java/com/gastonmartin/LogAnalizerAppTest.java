package com.gastonmartin;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Unit process for simple LogAnalizerApp.
 */
public class LogAnalizerAppTest
    extends TestCase
{
    /**
     * Create the process case
     *
     * @param testName name of the process case
     */
    public LogAnalizerAppTest(String testName )
    {
        super( testName );
    }

    /**
     * @return the suite of tests being tested
     */
    public static Test suite()
    {
        return new TestSuite( LogAnalizerAppTest.class );
    }

    /**
     * Rigourous Test :-)
     */
    public void testApp()
    {
        assertTrue( true );
    }
}
