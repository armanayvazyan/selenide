package com.codeborne.security.mobileid;

import com.codeborne.security.AuthenticationException;
import com.codeborne.security.mobileid.client.DigiDocServicePortType;
import com.codeborne.security.mobileid.client.DigiDocService_Service;
import com.codeborne.security.mobileid.client.DigiDocService_ServiceLocator;

import javax.xml.rpc.ServiceException;
import javax.xml.rpc.holders.IntHolder;
import javax.xml.rpc.holders.StringHolder;
import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.RemoteException;

public class MobileIDAuthenticator {
  private String language = "EST";
  private String serviceName = "Testimine";
  private String loginMessage = "Login?";
  private final String messagingMode = "asynchClientServer";
  private final int asyncConfiguration = 0;
  private final int mobileServiceGetCallRetryCount = 60;
  private final int mobileIdStatusPollInterval = 3000;

  DigiDocServicePortType digiDocServicePortType;

  public MobileIDAuthenticator() {
  }

  public MobileIDAuthenticator(String digidocServiceURL) throws MalformedURLException {
    setDigidocServiceURL(new URL(digidocServiceURL));
  }

  public MobileIDAuthenticator(String digidocServiceURL, String serviceName) throws MalformedURLException {
    setDigidocServiceURL(new URL(digidocServiceURL));
    this.serviceName = serviceName;
  }

  public MobileIDAuthenticator(URL digidocServiceURL) {
    setDigidocServiceURL(digidocServiceURL);
  }

  public MobileIDAuthenticator(URL digidocServiceURL, String serviceName) {
    setDigidocServiceURL(digidocServiceURL);
    this.serviceName = serviceName;
  }

  public final MobileIDAuthenticator setDigidocServiceURL(URL digidocServiceURL) {
    DigiDocService_Service digiDocService = new DigiDocService_ServiceLocator();
    try {
      digiDocServicePortType = digiDocService.getDigiDocService(digidocServiceURL);
    } catch (ServiceException e) {
      throw new RuntimeException("Failed to initialize Mobile-ID support", e);
    }
    return this;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public void setServiceName(String serviceName) {
    this.serviceName = serviceName;
  }

  public void setLoginMessage(String loginMessage) {
    this.loginMessage = loginMessage;
  }

  public MobileIDSession startSession(String phone) throws AuthenticationException {
    if (digiDocServicePortType == null) {
      throw new IllegalStateException("digidocServiceURL is not initialized");
    }

    IntHolder sessCode = new IntHolder();
    StringHolder result = new StringHolder();
    StringHolder firstName = new StringHolder();
    StringHolder lastName = new StringHolder();
    StringHolder personalCode = new StringHolder();
    StringHolder challenge = new StringHolder();

    try {
      digiDocServicePortType.mobileAuthenticate(null, null, phone, language, serviceName, loginMessage, generateSPChallenge(),
          messagingMode, asyncConfiguration, false, false, sessCode, result,
          personalCode, firstName, lastName, new StringHolder(), new StringHolder(), new StringHolder(), challenge,
          new StringHolder(), new StringHolder());
    }
    catch (RemoteException e) {
      String errorCode;
      if ("100".equals(e.getMessage()))
        errorCode = "SERVICE_ERROR";   // Teenuse üldine veasituatsioon.
      else if ("101".equals(e.getMessage()))
        errorCode = "INVALID_INPUT";   // Sisendparameetrid mittekorrektsel kujul.
      else if ("102".equals(e.getMessage()))
        errorCode = "MISSING_INPUT";   // Mõni kohustuslik sisendparameeter on määramata
      else if ("103".equals(e.getMessage()))
        errorCode = "METHOD_NOT_ALLOWED"; // Ligipääs antud meetodile antud parameetritega piiratud (näiteks kasutatav ServiceName ei ole teenuse pakkuja juures registreeritud).
      else if ("200".equals(e.getMessage()))
        errorCode = "AUTHENTICATION_ERROR"; // Teenuse üldine viga.
      else if ("201".equals(e.getMessage()))
        errorCode = "USER_CERTIFICATE_MISSING"; // Kasutaja sertifikaat puudub.
      else if ("202".equals(e.getMessage()))
        errorCode = "UNABLE_TO_TEST_USER_CERTIFICATE"; // Kasutaja sertifikaadi kehtivus ei ole võimalik kontrollida.
      else if ("300".equals(e.getMessage()))
        errorCode = "USER_PHONE_ERROR"; // Kasutajaga telefoniga seotud üldine viga.
      else if ("301".equals(e.getMessage()))
        errorCode = "NO_AGREEMENT";   // Kasutajal pole Mobiil-ID lepingut.
      else if ("302".equals(e.getMessage()))
        errorCode = "CERTIFICATE_REVOKED"; // Kasutaja sertifikaat ei kehti (OCSP vastus REVOKED).
      else if ("303".equals(e.getMessage()))
        errorCode = "NOT_ACTIVATED";  // Kasutajal pole Mobiil-ID aktiveeritud. Aktiveerimiseks tuleks minna aadressile http://mobiil.id.ee
      else
        errorCode = e.getMessage();

      throw new AuthenticationException(errorCode, e);
    }

    if (!"OK".equals(result.value))
      throw new AuthenticationException(result.value);

    return new MobileIDSession(sessCode.value, challenge.value, firstName.value, lastName.value, personalCode.value);
  }

  protected String generateSPChallenge() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 10; i++) sb.append((int)(Math.random() * 10));
    return sb.toString();
  }

  public MobileIDSession waitForLogin(MobileIDSession session) throws AuthenticationException {
    StringHolder status = new StringHolder("OUTSTANDING_TRANSACTION");
    int tryCount = 0;
    while (sleep(mobileIdStatusPollInterval) && "OUTSTANDING_TRANSACTION".equals(status.value) && tryCount < mobileServiceGetCallRetryCount) {
      try {
        digiDocServicePortType.getMobileAuthenticateStatus(session.sessCode, false, status, new StringHolder());
      }
      catch (RemoteException e) {
        throw new AuthenticationException(status.value, e);
      }
      tryCount++;
    }

    if (!"USER_AUTHENTICATED".equals(status.value))
      throw new AuthenticationException(status.value);

    return session;
  }

  private boolean sleep(int sleepTimeMilliseconds) {
    try {
      Thread.sleep(sleepTimeMilliseconds);
      return true;
    }
    catch (InterruptedException e) {
      return false;
    }
  }
}
