/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package wssec;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.apache.axis.Message;
import org.apache.axis.MessageContext;
import org.apache.axis.client.AxisClient;
import org.apache.axis.configuration.NullProvider;
import org.apache.axis.message.SOAPEnvelope;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ws.security.WSPasswordCallback;
import org.apache.ws.security.WSSecurityEngine;
import org.apache.ws.security.WSConstants;
import org.apache.ws.security.WSSecurityEngineResult;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.components.crypto.Crypto;
import org.apache.ws.security.components.crypto.CryptoFactory;
import org.apache.ws.security.handler.RequestData;
import org.apache.ws.security.handler.WSHandler;
import org.apache.ws.security.message.WSSecSignature;
import org.apache.ws.security.message.WSSecHeader;
import org.apache.ws.security.util.WSSecurityUtil;
import org.w3c.dom.Document;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.util.Vector;

/**
 * This is a test for WSS-40. Essentially it just tests that a message is signed using a
 * keyEntry from one keystore, and verified at the other end with a keystore with just the
 * CA cert in it.
 * 
 * http://issues.apache.org/jira/browse/WSS-40
 * 
 * Generate the CA keys/certs + export the CA cert to a keystore
 * 
 * openssl req -x509 -newkey rsa:1024 -keyout wss40CAKey.pem -out wss40CA.pem 
 * -config ca.config -days 3650
 * openssl x509 -outform DER -in wss40CA.pem -out wss40CA.crt
 * keytool -import -file wss40CA.crt -alias wss40CA -keystore wss40CA.jks
 * 
 * Generate the client keypair, make a csr, sign it with the CA key
 * 
 * keytool -genkey -validity 3650 -alias wss40 -keyalg RSA -keystore wss40.jks 
 * -dname "CN=Colm,OU=WSS4J,O=Apache,L=Dublin,ST=Leinster,C=IE"
 * keytool -certreq -alias wss40 -keystore wss40.jks -file wss40.cer
 * openssl ca -config ca.config -policy policy_anything -days 3650 -out wss40.pem 
 * -infiles wss40.cer
 * openssl x509 -outform DER -in wss40.pem -out wss40.crt
 * 
 * Import the CA cert into wss40.jks and import the new signed certificate
 * 
 * keytool -import -file wss40CA.crt -alias wss40CA -keystore wss40.jks
 * keytool -import -file wss40.crt -alias wss40 -keystore wss40.jks
 * 
 */
public class TestWSSecurityWSS40 extends TestCase implements CallbackHandler {
    private static final Log LOG = LogFactory.getLog(TestWSSecurityWSS40.class);
    private static final String SOAPMSG = 
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" 
        + "<SOAP-ENV:Envelope "
        +   "xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\" "
        +   "xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" "
        +   "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" 
        +   "<SOAP-ENV:Body>" 
        +       "<add xmlns=\"http://ws.apache.org/counter/counter_port_type\">" 
        +           "<value xmlns=\"\">15</value>" 
        +       "</add>" 
        +   "</SOAP-ENV:Body>" 
        + "</SOAP-ENV:Envelope>";
    private WSSecurityEngine secEngine = new WSSecurityEngine();
    private Crypto crypto = CryptoFactory.getInstance("wss40.properties");
    private Crypto cryptoCA = CryptoFactory.getInstance("wss40CA.properties");
    private MessageContext msgContext;
    private Message message;

    /**
     * TestWSSecurity constructor
     * 
     * @param name name of the test
     */
    public TestWSSecurityWSS40(String name) {
        super(name);
    }

    /**
     * JUnit suite
     * 
     * @return a junit test suite
     */
    public static Test suite() {
        return new TestSuite(TestWSSecurityWSS40.class);
    }

    /**
     * Setup method
     * 
     * @throws Exception Thrown when there is a problem in setup
     */
    protected void setUp() throws Exception {
        AxisClient tmpEngine = new AxisClient(new NullProvider());
        msgContext = new MessageContext(tmpEngine);
        message = getSOAPMessage();
    }

    /**
     * Constructs a soap envelope
     * 
     * @return soap envelope
     * @throws Exception if there is any problem constructing the soap envelope
     */
    protected Message getSOAPMessage() throws Exception {
        InputStream in = new ByteArrayInputStream(SOAPMSG.getBytes());
        Message msg = new Message(in);
        msg.setMessageContext(msgContext);
        return msg;
    }

    
    /**
     * Test signing a SOAP message using a BST.
     */
    public void testSignatureDirectReference() throws Exception {
        SOAPEnvelope unsignedEnvelope = message.getSOAPEnvelope();
        WSSecSignature sign = new WSSecSignature();
        sign.setUserInfo("wss40", "security");
        sign.setKeyIdentifierType(WSConstants.BST_DIRECT_REFERENCE);

        Document doc = unsignedEnvelope.getAsDocument();

        WSSecHeader secHeader = new WSSecHeader();
        secHeader.insertSecurityHeader(doc);
        Document signedDoc = sign.build(doc, crypto, secHeader);
        
        if (LOG.isDebugEnabled()) {
            String outputString = 
                org.apache.ws.security.util.XMLUtils.PrettyDocumentToString(signedDoc);
            LOG.debug(outputString);
        }
        //
        // Verify the signature
        //
        Vector results = verify(signedDoc, cryptoCA);
        
        //
        // Verify trust on the X509Certificate
        //
        MyHandler handler = new MyHandler();
        final RequestData reqData = new RequestData();
        reqData.setSigCrypto(cryptoCA);
        WSSecurityEngineResult result = 
            WSSecurityUtil.fetchActionResult(results, WSConstants.SIGN);

        X509Certificate cert = 
            (X509Certificate)result.get(WSSecurityEngineResult.TAG_X509_CERTIFICATE);
        assertTrue (cert != null);
        boolean trusted = handler.verifyCertificate(cert, reqData);
        if (!trusted) {
            fail("The X509 Certificate is not trusted!");
        }
    }
    
    
    /**
     * Test signing a SOAP message using Issuer Serial. Note that this should fail, as the
     * trust-store does not contain the cert corresponding to wss40, only the CA cert
     * wss40CA.
     */
    public void testSignatureIssuerSerial() throws Exception {
        SOAPEnvelope unsignedEnvelope = message.getSOAPEnvelope();
        WSSecSignature sign = new WSSecSignature();
        sign.setUserInfo("wss40", "security");
        sign.setKeyIdentifierType(WSConstants.ISSUER_SERIAL);

        Document doc = unsignedEnvelope.getAsDocument();

        WSSecHeader secHeader = new WSSecHeader();
        secHeader.insertSecurityHeader(doc);
        Document signedDoc = sign.build(doc, crypto, secHeader);
        
        if (LOG.isDebugEnabled()) {
            String outputString = 
                org.apache.ws.security.util.XMLUtils.PrettyDocumentToString(signedDoc);
            LOG.debug(outputString);
        }
        
        try {
            verify(signedDoc, cryptoCA);
            throw new Exception("Failure expected on issuer serial");
        } catch (WSSecurityException ex) {
            assertTrue(ex.getErrorCode() == WSSecurityException.FAILED_CHECK);
            // expected
        }
    }
    
    
    /**
     * Test signing a SOAP message using a BST. The signature verification passes, but the trust
     * verification will fail as the CA cert is out of date.
     */
    public void testSignatureBadCACert() throws Exception {
        SOAPEnvelope unsignedEnvelope = message.getSOAPEnvelope();
        WSSecSignature sign = new WSSecSignature();
        sign.setUserInfo("wss4jcertdsa", "security");
        sign.setKeyIdentifierType(WSConstants.BST_DIRECT_REFERENCE);

        Document doc = unsignedEnvelope.getAsDocument();

        WSSecHeader secHeader = new WSSecHeader();
        secHeader.insertSecurityHeader(doc);
        Document signedDoc = 
            sign.build(doc, CryptoFactory.getInstance("wss40badca.properties"), secHeader);
        
        if (LOG.isDebugEnabled()) {
            String outputString = 
                org.apache.ws.security.util.XMLUtils.PrettyDocumentToString(signedDoc);
            LOG.debug(outputString);
        }
        //
        // Verify the signature
        //
        Vector results = verify(signedDoc, CryptoFactory.getInstance("wss40badcatrust.properties"));
        
        //
        // Verify trust on the X509Certificate
        //
        MyHandler handler = new MyHandler();
        final RequestData reqData = new RequestData();
        reqData.setSigCrypto(CryptoFactory.getInstance("wss40badcatrust.properties"));
        WSSecurityEngineResult result = 
            WSSecurityUtil.fetchActionResult(results, WSConstants.SIGN);

        X509Certificate cert = 
            (X509Certificate)result.get(WSSecurityEngineResult.TAG_X509_CERTIFICATE);
        assertTrue (cert != null);
        try {
            handler.verifyCertificate(cert, reqData);
            fail("Failure expected on bad CA cert!");
        } catch (WSSecurityException ex) {
            // expected
        }
    }
    
    
    /**
     * Verifies the soap envelope
     * <p/>
     * 
     * @param doc 
     * @throws Exception Thrown when there is a problem in verification
     */
    private Vector verify(Document doc, Crypto crypto) throws WSSecurityException {
        Vector results = secEngine.processSecurityHeader(
            doc, null, this, crypto
        );
        if (LOG.isDebugEnabled()) {
            LOG.debug("Verfied and decrypted message:");
            String outputString = 
                org.apache.ws.security.util.XMLUtils.PrettyDocumentToString(doc);
            LOG.debug(outputString);
        }
        return results;
    }

    public void handle(Callback[] callbacks)
        throws IOException, UnsupportedCallbackException {
        for (int i = 0; i < callbacks.length; i++) {
            if (callbacks[i] instanceof WSPasswordCallback) {
                WSPasswordCallback pc = (WSPasswordCallback) callbacks[i];
                /*
                 * here call a function/method to lookup the password for
                 * the given identifier (e.g. a user name or keystore alias)
                 * e.g.: pc.setPassword(passStore.getPassword(pc.getIdentfifier))
                 * for Testing we supply a fixed name here.
                 */
                pc.setPassword("security");
            } else {
                throw new UnsupportedCallbackException(callbacks[i], "Unrecognized Callback");
            }
        }
    }
    
    /**
     * a trivial extension of the WSHandler type
     */
    public static class MyHandler extends WSHandler {
        
        public Object 
        getOption(String key) {
            return null;
        }
        
        public void 
        setProperty(
            Object msgContext, 
            String key, 
            Object value
        ) {
        }

        public Object 
        getProperty(Object ctx, String key) {
            return null;
        }
    
        public void 
        setPassword(Object msgContext, String password) {
        }
        
        public String 
        getPassword(Object msgContext) {
            return null;
        }

        boolean verifyCertificate(
            X509Certificate cert,
            RequestData reqData 
        ) throws org.apache.ws.security.WSSecurityException {
            return verifyTrust(
                cert, 
                reqData
            );
        }
    }
}
