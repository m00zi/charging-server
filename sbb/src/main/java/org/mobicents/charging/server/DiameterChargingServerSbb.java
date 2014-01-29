/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2012, TeleStax and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for
 * a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.charging.server;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.slee.ActivityContextInterface;
import javax.slee.ChildRelation;
import javax.slee.CreateException;
import javax.slee.InitialEventSelector;
import javax.slee.SLEEException;
import javax.slee.Sbb;
import javax.slee.SbbContext;
import javax.slee.TransactionRequiredLocalException;
import javax.slee.facilities.TimerEvent;
import javax.slee.facilities.TimerFacility;
import javax.slee.facilities.TimerOptions;
import javax.slee.facilities.TimerPreserveMissed;
import javax.slee.facilities.Tracer;
import javax.slee.resource.ResourceAdaptorTypeID;
import javax.slee.serviceactivity.ServiceStartedEvent;

import net.java.slee.resource.diameter.base.events.avp.DiameterAvp;
import net.java.slee.resource.diameter.base.events.avp.DiameterAvpType;
import net.java.slee.resource.diameter.base.events.avp.DiameterResultCode;
import net.java.slee.resource.diameter.base.events.avp.GroupedAvp;
import net.java.slee.resource.diameter.cca.events.avp.CcMoneyAvp;
import net.java.slee.resource.diameter.cca.events.avp.CcRequestType;
import net.java.slee.resource.diameter.cca.events.avp.CcUnitType;
import net.java.slee.resource.diameter.cca.events.avp.CreditControlResultCode;
import net.java.slee.resource.diameter.cca.events.avp.FinalUnitActionType;
import net.java.slee.resource.diameter.cca.events.avp.FinalUnitIndicationAvp;
import net.java.slee.resource.diameter.cca.events.avp.GrantedServiceUnitAvp;
import net.java.slee.resource.diameter.cca.events.avp.MultipleServicesCreditControlAvp;
import net.java.slee.resource.diameter.cca.events.avp.RequestedServiceUnitAvp;
import net.java.slee.resource.diameter.cca.events.avp.SubscriptionIdAvp;
import net.java.slee.resource.diameter.cca.events.avp.SubscriptionIdType;
import net.java.slee.resource.diameter.cca.events.avp.UsedServiceUnitAvp;
import net.java.slee.resource.diameter.ro.RoAvpFactory;
import net.java.slee.resource.diameter.ro.RoProvider;
import net.java.slee.resource.diameter.ro.RoServerSessionActivity;
import net.java.slee.resource.diameter.ro.events.RoCreditControlAnswer;
import net.java.slee.resource.diameter.ro.events.RoCreditControlRequest;

import org.mobicents.charging.server.account.AccountBalanceManagement;
import org.mobicents.charging.server.account.CreditControlInfo;
import org.mobicents.charging.server.account.CreditControlInfo.ErrorCodeType;
import org.mobicents.charging.server.account.CreditControlUnit;
import org.mobicents.charging.server.ratingengine.RatingEngineClient;
import org.mobicents.charging.server.ratingengine.RatingInfo;
import org.mobicents.charging.server.data.DataSource;
import org.mobicents.charging.server.data.UserSessionInfo;
import org.mobicents.slee.ChildRelationExt;
import org.mobicents.slee.SbbContextExt;
import org.mobicents.slee.SbbLocalObjectExt;

/**
 * Diameter Charging Server Root SBB.
 * 
 * @author ammendonca
 * @author baranowb
 * @author rsaranathan
 */
public abstract class DiameterChargingServerSbb extends BaseSbb implements Sbb, DiameterChargingServer {

	private static final long DEFAULT_VALIDITY_TIME = 86400;
	private static final TimerOptions DEFAULT_TIMER_OPTIONS = new TimerOptions(0, TimerPreserveMissed.ALL);

	private boolean performRating = true; // true = centralized, false = decentralized (ie, has been done by CTF (eg SIP AS))

	private static TimerOptions createDefaultTimerOptions() {
		TimerOptions timerOptions = new TimerOptions();
		timerOptions.setPreserveMissed(TimerPreserveMissed.ALL);
		return timerOptions;
	}

	private SbbContextExt sbbContextExt; // This SBB's SbbContext

	private Tracer tracer;
	private TimerFacility timerFacility;

	private RoAvpFactory avpFactory;
	//private RoActivityContextInterfaceFactory roAcif;
	private RoProvider roProvider;

	/*
	 * Centralized Unit Determination. Please note that 3GPP standards are only
	 * Centralized Unit Determination with Centralized Rating Engine. See
	 * "Section 5.2.2 Charging Scenarios" of the 3GPP R8 guide for more
	 * information.
	 */
	private static HashMap<Integer, Integer> serviceIdUnits;

	private static HashMap<String, String> abmfAVPs = new HashMap<String, String>();

	// ---------------------------- SLEE Callbacks ----------------------------

	public void setSbbContext(SbbContext context) {
		this.sbbContextExt = (SbbContextExt) context;
		this.tracer = sbbContextExt.getTracer("CS-Core");
		this.timerFacility = this.sbbContextExt.getTimerFacility();

		ResourceAdaptorTypeID raTypeID = new ResourceAdaptorTypeID("Diameter Ro", "java.net", "0.8.1");
		this.roProvider = (RoProvider) sbbContextExt.getResourceAdaptorInterface(raTypeID, "DiameterRo");
		//this.roAcif = (RoActivityContextInterfaceFactory) sbbContextExt.getActivityContextInterfaceFactory(raTypeID);

		this.avpFactory = this.roProvider.getRoAvpFactory();
	}

	public void unsetSbbContext() {
		this.sbbContextExt = null;
		this.tracer = null;
	}

	/**
	 * Convenience method to retrieve the SbbContext object stored in
	 * setSbbContext.
	 * 
	 * @return this SBB's SbbContext object
	 */
	protected SbbContext getSbbContext() {
		return sbbContextExt;
	}

	// ---------------------------- Child Relation ----------------------------
	public abstract ChildRelation getAccountBalanceManagementChildRelation();

	public abstract ChildRelation getDatasourceChildRelation();

	public abstract ChildRelation getRatingEngineChildRelation();

	// --------------------------------- IES ----------------------------------
	public InitialEventSelector onCreditControlRequestInitialEventSelect(InitialEventSelector ies) {
		RoCreditControlRequest event = (RoCreditControlRequest) ies.getEvent();

		ies.setCustomName(event.getSessionId());
        // ammendonca: only INITIAL are initial events
        boolean isInitial = (event.getCcRequestType() == CcRequestType.INITIAL_REQUEST || event.getCcRequestType() == CcRequestType.EVENT_REQUEST);
        tracer.info("[--] Received CCR is " + (isInitial ? "" : "non-") + "initial.");
		ies.setInitialEvent(isInitial);

		return ies;
	}

	// ---------------------------- Helper Methods ----------------------------

	private static final String DATASOURCE_CHILD_NAME = "DATASOURCE";
	protected DataSource getDatasource() throws TransactionRequiredLocalException, IllegalArgumentException, NullPointerException, SLEEException, CreateException {
		ChildRelationExt cre = (ChildRelationExt) getDatasourceChildRelation();
		SbbLocalObjectExt sbbLocalObject = cre.get(DATASOURCE_CHILD_NAME);
		if (sbbLocalObject == null) {
			sbbLocalObject = cre.create(DATASOURCE_CHILD_NAME);
		}

		return (DataSource) sbbLocalObject;
	}

	private static final String ABMF_CHILD_NAME = "ACC_MANAGER";
	protected AccountBalanceManagement getAccountManager() throws TransactionRequiredLocalException, IllegalArgumentException, NullPointerException, SLEEException, CreateException {
		ChildRelationExt cre = (ChildRelationExt) getAccountBalanceManagementChildRelation();
		SbbLocalObjectExt sbbLocalObject = cre.get(ABMF_CHILD_NAME);
		if (sbbLocalObject == null) {
			sbbLocalObject = cre.create(ABMF_CHILD_NAME);
		}

		return (AccountBalanceManagement) sbbLocalObject;
	}

	private static final String RATING_CHILD_NAME = "RE_MANAGER";
	protected RatingEngineClient getRatingEngineManager() throws TransactionRequiredLocalException, IllegalArgumentException, NullPointerException, SLEEException, CreateException {
		ChildRelationExt cre = (ChildRelationExt) getRatingEngineChildRelation();
		SbbLocalObjectExt sbbLocalObject = cre.get(RATING_CHILD_NAME);
		if (sbbLocalObject == null) {
			sbbLocalObject = cre.create(RATING_CHILD_NAME);
		}

		return (RatingEngineClient) sbbLocalObject;
	}

	/**
	 * @param errorCodeType
	 * @return
	 */
	protected long getResultCode(ErrorCodeType errorCodeType) {
		switch (errorCodeType) {
		// actually return codes are not 100% ok here.
		case InvalidUser:
			return CreditControlResultCode.DIAMETER_USER_UNKNOWN;
		case BadRoamingCountry:
			return CreditControlResultCode.DIAMETER_END_USER_SERVICE_DENIED;
		case NoServiceForUser:
			return CreditControlResultCode.DIAMETER_END_USER_SERVICE_DENIED;
		case NotEnoughBalance:
			return CreditControlResultCode.DIAMETER_CREDIT_LIMIT_REACHED;
		case InvalidContent:
		case MalformedRequest:
		case AccountingConnectionErr:
		default:
			return DiameterResultCode.DIAMETER_UNABLE_TO_DELIVER;
		}
	}

	// ---------------------------- Event Handlers ----------------------------

	public void onServiceStartedEvent(ServiceStartedEvent event, ActivityContextInterface aci) {
		if (tracer.isInfoEnabled()) {
			tracer.info("==============================================================================");
			tracer.info("==                 Mobicents Charging Server v1.0 [STARTED]                 ==");
			tracer.info("==                                  - . -                                   ==");
			tracer.info("==              Thank you for running Mobicents Community code              ==");
			tracer.info("==   For Commercial Grade Support, please request a TelScale Subscription   ==");
			tracer.info("==                         http://www.telestax.com/                         ==");
			tracer.info("==============================================================================");
		}

		DataSource ds;
		try {
			ds = getDatasource();
			if (tracer.isInfoEnabled()) {
				tracer.info("[><] Got DataSource Child SBB Local Interface [" + ds + "]");
			}
			ds.init();
		}
		catch (Exception e) {
			tracer.severe("[xx] Unable to fetch Datasource Child SBB .");
			return;
		}

		AccountBalanceManagement am;
		try {
			am = getAccountManager();
			if (tracer.isInfoEnabled()) {
				tracer.info("[><] Got Account Balance Management Child SBB Local Interface [" + am + "]");
			}
		}
		catch (Exception e) {
			tracer.severe("[xx] Unable to fetch Account and Balance Management Child SBB .");
			return;
		}

		try {
			Context ctx = (Context) new InitialContext().lookup("java:comp/env");
			boolean loadUsersFromCSV = (Boolean) ctx.lookup("loadUsersFromCSV");
			performRating = (Boolean) ctx.lookup("performRating");

			String abmfAVPsProp = ((String) ctx.lookup("ABMF_AVPs"));
			try {
				String[] avps = abmfAVPsProp.trim().split(",");
				for (String avp : avps) {
					String[] codeName = avp.trim().split("=");
					if (tracer.isInfoEnabled()) {
						tracer.info("[><] Mapping AVP with Code " + codeName[0] + " as '" + codeName[1] + "' on received CCRs for ABMF Data.");
					}
					abmfAVPs.put(codeName[0], codeName[1]);
				}
			}
			catch (Exception e) {
				tracer.warning("[!!] Error reading ABMF Data AVPs. Format should be: code=name,code2=name2,... No custom data will be passed.");
			}

			if (loadUsersFromCSV) {
				try {

					Properties props = new Properties();
					props.load(this.getClass().getClassLoader().getResourceAsStream("users.properties"));
					for (Object key : props.keySet()) {
						String msisdn = (String) key;
						// am.addUser(imsi, Long.valueOf(props.getProperty(imsi)));
						// TODO: remove the properties to database mapping later on. useful for now
						ds.updateUser(msisdn, Long.valueOf(props.getProperty(msisdn)));
					}
					if (tracer.isInfoEnabled()) {
						tracer.info("[--] Loaded users from properties file.");
					}
				}
				catch (Exception e) {
					tracer.warning("[!!] Unable to load users from properties file. Allowing everything!");
					am.setBypass(true);
				}
			}
		}
        catch(Exception e){
			tracer.warning("[!!] Unable to retrieve loadUsersFromCSV flag from env-entry");
		}
		if (tracer.isInfoEnabled()) {
			tracer.info("[--] Dumping users state...");
			am.dump("%");
		}

		// Load service id units (Unit Determination)
		// TODO: Need to port this to database.
		try {
			Properties props = new Properties();
			props.load(this.getClass().getClassLoader().getResourceAsStream("serviceid-units.properties"));
			serviceIdUnits = new HashMap<Integer, Integer>();
			for (Object key : props.keySet()) {
				String serviceId = ((String) key).trim();
				String unitTypeID = props.getProperty(serviceId).trim();
				serviceIdUnits.put(Integer.valueOf(serviceId),Integer.valueOf(unitTypeID));
			}
			tracer.info("[--] Loaded service id units from properties file. Dumping info.");
			// dump info...
			for (Object o : serviceIdUnits.entrySet()) {
				Map.Entry entry = (Map.Entry) o;
				String key = null;
				String val = null;
				if (entry.getKey() != null) {
					key = entry.getKey().toString();
				}
				if (entry.getValue() != null) {
					val = entry.getValue().toString();
				}
				tracer.info("Service-ID:" + key + " => Unit-Type-ID:" + val);
			}
		}
		catch (Exception e) {
			tracer.warning("[!!] Unable to load service id units from properties file. Allowing everything!", e);
			am.setBypass(true);
		}
	}

	public void onCreditControlRequest(RoCreditControlRequest ccr, ActivityContextInterface aci) {
		String serviceContextId = "Some-Service-Context-Id";

		String sessionId = ccr.getSessionId();

		UserSessionInfo sessionInfo = getSessionInfo();
		if (sessionInfo == null) {
			sessionInfo = new UserSessionInfo();
			sessionInfo.setSessionStartTime(System.currentTimeMillis());
		}
		sessionInfo.setCcr(ccr);
		sessionInfo.setSessionId(sessionId);
		setSessionInfo(sessionInfo);

		if (tracer.isInfoEnabled()) {
			tracer.info("[<<] SID<" + ccr.getSessionId() + "> Received Credit-Control-Request [" + ccr.getCcRequestType().toString() + "]");
			if (tracer.isFineEnabled()) {
				tracer.fine(ccr.toString());
			}
		}

		// Some common ops. may be moved to proper places to avoid unnecessary ops
		RoServerSessionActivity ccServerActivity = (RoServerSessionActivity) aci.getActivity();

		SubscriptionIdType endUserType = null; 
		String endUserId = null;

		// Get the Subscription-Id and it's Type .. for now we only care for first, still we log all
		SubscriptionIdAvp[] subscriptionIds = ccr.getSubscriptionIds();

		if (subscriptionIds != null && subscriptionIds.length > 0) {
			endUserType = subscriptionIds[0].getSubscriptionIdType();
			endUserId = subscriptionIds[0].getSubscriptionIdData();
			if (tracer.isInfoEnabled()) {
				for (SubscriptionIdAvp subscriptionId : subscriptionIds) {
					tracer.info("[--] SID<" + sessionId + "> Received CCR has Subcription-Id with type '" + subscriptionId.getSubscriptionIdType() + "' and value '" + subscriptionId.getSubscriptionIdData() + "'.");
				}
			}
		}
		else {
			tracer.severe("[xx] SID<" + sessionId + "> Subscription-Id AVP missing in CCR. Rejecting CCR.");
			createCCA(ccServerActivity, ccr, null, DiameterResultCode.DIAMETER_MISSING_AVP);
		}

		RoCreditControlAnswer cca = null;
		if (endUserId == null) {
			tracer.severe("[xx] SID<" + sessionId + "> Subscription-Id AVP is present but could not read it's data. Rejecting CCR.");
			cca = createCCA(ccServerActivity, ccr, null, DiameterResultCode.DIAMETER_MISSING_AVP);
			sendCCA(cca, aci, true);
			return;
		}

		AccountBalanceManagement accountBalanceManagement = null;
		RatingEngineClient ratingEngineManagement = null;

		switch (ccr.getCcRequestType()) {
		// INITIAL_REQUEST 1
		case INITIAL_REQUEST:
			// ... intentionally did not break;
			// UPDATE_REQUEST 2
		case UPDATE_REQUEST:
			// FIXME: We may need to set timeout timer.. but not like this.
			// timerFacility.setTimer(aci, null, System.currentTimeMillis() + 15000, DEFAULT_TIMER_OPTIONS);

			try {
				accountBalanceManagement = getAccountManager();

				// retrieve service information from AVPs
				serviceContextId = ccr.getServiceContextId();
				if (serviceContextId == null) {
					tracer.severe("[xx] SID<" + sessionId + "> Service-Context-Id AVP missing in CCR. Rejecting CCR.");
					// TODO: include missing avp - its a "SHOULD"
					createCCA(ccServerActivity, ccr, null, DiameterResultCode.DIAMETER_MISSING_AVP);
				}
				else {
					if (serviceContextId.equals("")) {
						tracer.severe("[xx] SID<" + sessionId + "> Service-Context-Id AVP is empty in CCR. Rejecting CCR.");
						createCCA(ccServerActivity, ccr, null, DiameterResultCode.DIAMETER_INVALID_AVP_VALUE);
					}
				}

				// TODO: For Ro, support Service-Information AVP

				try {
					ratingEngineManagement = getRatingEngineManager();
				}
				catch (Exception e) {
					tracer.severe("[xx] Unable to fetch Rating Engine Management Child SBB.", e);
					return;
				}

				List<CreditControlInfo> reservations = new ArrayList<CreditControlInfo>();
				long resultCode = DiameterResultCode.DIAMETER_SUCCESS;

				MultipleServicesCreditControlAvp[] multipleServicesCreditControlAvps = ccr.getMultipleServicesCreditControls();
				if (multipleServicesCreditControlAvps != null && tracer.isInfoEnabled()) {
					tracer.info("[--] SID<" + sessionId + "> Received CCR has Multiple-Services-Credit-Control AVP with length = " + multipleServicesCreditControlAvps.length);
				}
				for (MultipleServicesCreditControlAvp mscc : multipleServicesCreditControlAvps) {

					long[] serviceIds = mscc.getServiceIdentifiers();

					RequestedServiceUnitAvp rsu = mscc.getRequestedServiceUnit();
					ArrayList<CreditControlUnit> ccRequestedUnits = new ArrayList<CreditControlUnit>();

					long requestedUnits = 0;
					// Input Octets
					requestedUnits = rsu.getCreditControlInputOctets();
					if (requestedUnits > 0) {
						CreditControlUnit ccUnit = new CreditControlUnit();
						ccUnit.setUnitType(CcUnitType.INPUT_OCTETS);
						if (performRating) {
							double rateForService = getRateForService(ccr, ratingEngineManagement, serviceIds[0], CcUnitType.INPUT_OCTETS.getValue(), requestedUnits);
							ccUnit.setRateForService(rateForService);
							// FIXME: This is not right. Rating should convert to monetary units...
							ccUnit.setRequestedAmount((long) Math.ceil(requestedUnits * rateForService));
						}
						ccUnit.setRequestedUnits(requestedUnits);
						ccRequestedUnits.add(ccUnit);
					}
					// Money 
					CcMoneyAvp moneyUnitsTmp = rsu.getCreditControlMoneyAvp();
					if (moneyUnitsTmp != null) {
						requestedUnits = moneyUnitsTmp.getUnitValue().getValueDigits();
						if (requestedUnits > 0) {
							CreditControlUnit ccUnit = new CreditControlUnit();
							ccUnit.setUnitType(CcUnitType.MONEY);
							if (performRating) {
								double rateForService = getRateForService(ccr, ratingEngineManagement, serviceIds[0],CcUnitType.MONEY.getValue(), requestedUnits);
								ccUnit.setRateForService(rateForService);
								// FIXME: This is not right. Rating should convert to monetary units...
								ccUnit.setRequestedAmount((long) Math.ceil(requestedUnits * rateForService));
							}
							ccUnit.setRequestedUnits(requestedUnits);
							ccUnit.setCcMoney(rsu.getCreditControlMoneyAvp());
							ccRequestedUnits.add(ccUnit);
						}
					}
					// Output Octets 
					requestedUnits = rsu.getCreditControlOutputOctets();
					if (requestedUnits > 0) {
						CreditControlUnit ccUnit = new CreditControlUnit();
						ccUnit.setUnitType(CcUnitType.OUTPUT_OCTETS);
						if (performRating) {
							double rateForService = getRateForService(ccr, ratingEngineManagement, serviceIds[0], CcUnitType.OUTPUT_OCTETS.getValue(), requestedUnits);
							ccUnit.setRateForService(rateForService);
							// FIXME: This is not right. Rating should convert to monetary units...
							ccUnit.setRequestedAmount((long) Math.ceil(requestedUnits * rateForService));
						}
						ccUnit.setRequestedUnits(requestedUnits);
						ccRequestedUnits.add(ccUnit);
					}
					// Service Specific Units
					requestedUnits = rsu.getCreditControlServiceSpecificUnits();
					if (requestedUnits > 0) {
						CreditControlUnit ccUnit = new CreditControlUnit();
						ccUnit.setUnitType(CcUnitType.SERVICE_SPECIFIC_UNITS);
						if (performRating) {
							double rateForService = getRateForService(ccr, ratingEngineManagement, serviceIds[0], CcUnitType.SERVICE_SPECIFIC_UNITS.getValue(), requestedUnits);
							ccUnit.setRateForService(rateForService);
							// FIXME: This is not right. Rating should convert to monetary units...
							ccUnit.setRequestedAmount((long) Math.ceil(requestedUnits * rateForService));
						}
						ccUnit.setRequestedUnits(requestedUnits);
						ccRequestedUnits.add(ccUnit);
					}
					// Time 
					requestedUnits = rsu.getCreditControlTime();
					if (requestedUnits > 0) {
						CreditControlUnit ccUnit = new CreditControlUnit();
						ccUnit.setUnitType(CcUnitType.TIME);
						if (performRating) {
							double rateForService = getRateForService(ccr, ratingEngineManagement, serviceIds[0], CcUnitType.TIME.getValue(), requestedUnits);
							ccUnit.setRateForService(rateForService);
							// FIXME: This is not right. Rating should convert to monetary units...
							ccUnit.setRequestedAmount((long) Math.ceil(requestedUnits * rateForService));
						}
						ccUnit.setRequestedUnits(requestedUnits);
						ccRequestedUnits.add(ccUnit);
					}
					// Total Octets
					requestedUnits = rsu.getCreditControlTotalOctets();
					if (requestedUnits > 0) {
						CreditControlUnit ccUnit = new CreditControlUnit();
						ccUnit.setUnitType(CcUnitType.TOTAL_OCTETS);
						if (performRating) {
							double rateForService = getRateForService(ccr, ratingEngineManagement, serviceIds[0], CcUnitType.TOTAL_OCTETS.getValue(), requestedUnits);
							ccUnit.setRateForService(rateForService);
							// FIXME: This is not right. Rating should convert to monetary units...
							ccUnit.setRequestedAmount((long) Math.ceil(requestedUnits * rateForService));
						}
						ccUnit.setRequestedUnits(requestedUnits);
						ccRequestedUnits.add(ccUnit);
					}

					// if its UPDATE, lets first update data
					long usedUnitsInputCount = 0;
					long usedUnitsMoneyCount = 0;
					long usedUnitsOutputCount = 0;
					long usedUnitsServiceSpecificCount = 0;
					long usedUnitsTimeCount = 0;
					long usedUnitsTotalCount = 0;
					if (ccr.getCcRequestType() == CcRequestType.UPDATE_REQUEST) {
						// update used units for each CC-Type.
						UsedServiceUnitAvp[] usedUnits = mscc.getUsedServiceUnits();

						sessionInfo = getSessionInfo();
						CreditControlInfo reservedInfo = sessionInfo.getReservations().get(sessionInfo.getReservations().size()-1);
						ArrayList<CreditControlUnit> reservedCCUnits = reservedInfo.getCcUnits();

						for (UsedServiceUnitAvp usedUnit : usedUnits) {
							if (usedUnit.getCreditControlInputOctets() > 0) {
								usedUnitsInputCount += usedUnit.getCreditControlInputOctets();
								for (int i = 0; i < ccRequestedUnits.size(); i++) {
									CreditControlUnit ccUnit = ccRequestedUnits.get(i);
									if (ccUnit.getUnitType() == CcUnitType.INPUT_OCTETS) {
										ccUnit.setUsedUnits(usedUnitsInputCount);
									}
									for (int j = 0; j < reservedCCUnits.size(); j++) {
										CreditControlUnit reservedCCUnit = reservedCCUnits.get(j);
										if (reservedCCUnit.getUnitType() == CcUnitType.INPUT_OCTETS) {
											// Copy the reserved amount from the last session into this session so that ABMF can update used units.
											ccUnit.setReservedUnits(reservedCCUnit.getReservedUnits());
											ccUnit.setReservedAmount(reservedCCUnit.getReservedAmount());
											ccUnit.setUsedAmount((long)Math.ceil(reservedCCUnit.getRateForService() * ccUnit.getUsedUnits()));
										}
									}
								}
							}
							moneyUnitsTmp = usedUnit.getCreditControlMoneyAvp();
							if (moneyUnitsTmp != null && moneyUnitsTmp.getUnitValue().getValueDigits() > 0) {
								usedUnitsMoneyCount += moneyUnitsTmp.getUnitValue().getValueDigits();
								for (int i = 0; i < ccRequestedUnits.size(); i++) {
									CreditControlUnit ccUnit = ccRequestedUnits.get(i);
									if (ccUnit.getUnitType() == CcUnitType.MONEY) {
										ccUnit.setUsedUnits(usedUnitsMoneyCount);
									}
									for (int j = 0; j < reservedCCUnits.size(); j++) {
										CreditControlUnit reservedCCUnit = reservedCCUnits.get(j);
										if (reservedCCUnit.getUnitType() == CcUnitType.MONEY) {
											// Copy the reserved amount from the last session into this session so that ABMF can update used units.
											ccUnit.setReservedUnits(reservedCCUnit.getReservedUnits());
											ccUnit.setReservedAmount(reservedCCUnit.getReservedAmount());
											ccUnit.setUsedAmount((long)Math.ceil(reservedCCUnit.getRateForService() * ccUnit.getUsedUnits()));
										}
									}
								}
							}
							if (usedUnit.getCreditControlOutputOctets() > 0) {
								usedUnitsOutputCount += usedUnit.getCreditControlOutputOctets();
								for (int i = 0; i < ccRequestedUnits.size(); i++) {
									CreditControlUnit ccUnit = ccRequestedUnits.get(i);
									if (ccUnit.getUnitType() == CcUnitType.OUTPUT_OCTETS) {
										ccUnit.setUsedUnits(usedUnitsOutputCount);
									}
									for (int j = 0; j < reservedCCUnits.size(); j++) {
										CreditControlUnit reservedCCUnit = reservedCCUnits.get(j);
										if (reservedCCUnit.getUnitType() == CcUnitType.OUTPUT_OCTETS){
											// Copy the reserved amount from the last session into this session so that ABMF can update used units.
											ccUnit.setReservedUnits(reservedCCUnit.getReservedUnits());
											ccUnit.setReservedAmount(reservedCCUnit.getReservedAmount());
											ccUnit.setUsedAmount((long)Math.ceil(reservedCCUnit.getRateForService() * ccUnit.getUsedUnits()));
										}
									}
								}
							}
							if (usedUnit.getCreditControlServiceSpecificUnits() > 0) {
								usedUnitsServiceSpecificCount += usedUnit.getCreditControlServiceSpecificUnits();
								for (int i = 0; i < ccRequestedUnits.size(); i++) {
									CreditControlUnit ccUnit = ccRequestedUnits.get(i);
									if (ccUnit.getUnitType() == CcUnitType.SERVICE_SPECIFIC_UNITS) {
										ccUnit.setUsedUnits(usedUnitsServiceSpecificCount);
									}
									for (int j = 0; j < reservedCCUnits.size(); j++) {
										CreditControlUnit reservedCCUnit = reservedCCUnits.get(j);
										if (reservedCCUnit.getUnitType() == CcUnitType.SERVICE_SPECIFIC_UNITS) {
											// Copy the reserved amount from the last session into this session so that ABMF can update used units.
											ccUnit.setReservedUnits(reservedCCUnit.getReservedUnits());
											ccUnit.setReservedAmount(reservedCCUnit.getReservedAmount());
											ccUnit.setUsedAmount((long)Math.ceil(reservedCCUnit.getRateForService() * ccUnit.getUsedUnits()));
										}
									}
								}
							}
							if (usedUnit.getCreditControlTime() > 0) {
								usedUnitsTimeCount += usedUnit.getCreditControlTime();
								for (int i = 0; i < ccRequestedUnits.size(); i++) {
									CreditControlUnit ccUnit = ccRequestedUnits.get(i);
									if (ccUnit.getUnitType() == CcUnitType.TIME) {
										ccUnit.setUsedUnits(usedUnitsTimeCount);
									}
									for (int j = 0; j < reservedCCUnits.size(); j++) {
										CreditControlUnit reservedCCUnit = reservedCCUnits.get(j);
										if (reservedCCUnit.getUnitType() == CcUnitType.TIME) {
											// Copy the reserved amount from the last session into this session so that ABMF can update used units.
											ccUnit.setReservedUnits(reservedCCUnit.getReservedUnits());
											ccUnit.setReservedAmount(reservedCCUnit.getReservedAmount());
											ccUnit.setUsedAmount((long)Math.ceil(reservedCCUnit.getRateForService() * ccUnit.getUsedUnits()));
										}
									}
								}
							}
							if (usedUnit.getCreditControlTotalOctets() > 0) {
								usedUnitsTotalCount += usedUnit.getCreditControlTotalOctets();
								for (int i = 0; i < ccRequestedUnits.size(); i++) {
									CreditControlUnit ccUnit = ccRequestedUnits.get(i);
									if (ccUnit.getUnitType() == CcUnitType.TOTAL_OCTETS) {
										ccUnit.setUsedUnits(usedUnitsTotalCount);
									}
									for (int j = 0; j < reservedCCUnits.size(); j++) {
										CreditControlUnit reservedCCUnit = reservedCCUnits.get(j);
										if (reservedCCUnit.getUnitType() == CcUnitType.TOTAL_OCTETS) {
											// Copy the reserved amount from the last session into this session so that ABMF can update used units.
											ccUnit.setReservedUnits(reservedCCUnit.getReservedUnits());
											ccUnit.setReservedAmount(reservedCCUnit.getReservedAmount());
											ccUnit.setUsedAmount((long)Math.ceil(reservedCCUnit.getRateForService() * ccUnit.getUsedUnits()));
										}
									}
								}
							}
						}

						// Build Credit Control Info Request to ABMF
						CreditControlInfo ccInfo = new CreditControlInfo();
						ccInfo.setEventTimestamp(System.currentTimeMillis());
						ccInfo.setEventType(ccr.getCcRequestType().toString());
						ccInfo.setRequestNumber((int) ccr.getCcRequestNumber());
						ccInfo.setSessionId(sessionId);
						ccInfo.setSubscriptionId(endUserId);
						ccInfo.setSubscriptionIdType(endUserType);

						ccInfo.setCcUnits(ccRequestedUnits);

						// Call ABMF with this Credit Control Info 
						accountBalanceManagement.updateRequest(ccInfo);

						// Store Credit Control Info in CMP
						sessionInfo = getSessionInfo();
						sessionInfo.setCcr(ccr);
						sessionInfo.setServiceIds(serviceIds);
						sessionInfo.setEndUserId(endUserId);
						setSessionInfo(sessionInfo);

						return; // we'll continue @ resumeOnCreditControlRequest(..)
					}
					else {
						// Initial Request

						// Build Credit Control Info Request to ABMF
						CreditControlInfo ccInfo = new CreditControlInfo();
						ccInfo.setEventTimestamp(System.currentTimeMillis());
						ccInfo.setEventType(ccr.getCcRequestType().toString());
						ccInfo.setRequestNumber((int) ccr.getCcRequestNumber());
						ccInfo.setSessionId(sessionId);
						ccInfo.setSubscriptionId(endUserId);
						ccInfo.setSubscriptionIdType(endUserType);
						ccInfo.setCcUnits(ccRequestedUnits);

						// Iterate CCR to capture needed AVPs
						for (DiameterAvp avp : ccr.getAvps()) {
							fetchDataFromAvp(avp, ccInfo);
						}

						// Call ABMF with this Credit Control Info
						accountBalanceManagement.initialRequest(ccInfo);

						// Store Credit Control Info in CMP
						sessionInfo = getSessionInfo();
						sessionInfo.setCcr(ccr);
						sessionInfo.setServiceIds(serviceIds);
						sessionInfo.setEndUserId(endUserId);
						sessionInfo.setEndUserType(endUserType);
						setSessionInfo(sessionInfo);

						return; // we'll continue @ resumeOnCreditControlRequest(..)
					}
				}

				if (reservations.size() > 0) {
					cca = createCCA(ccServerActivity, ccr, reservations, resultCode);
				}
				else {
					cca = createCCA(ccServerActivity, ccr, null, DiameterResultCode.DIAMETER_MISSING_AVP);
				}
				ccServerActivity.sendRoCreditControlAnswer(cca);
			}
			catch (Exception e) {
				tracer.severe("[xx] SID<" + ccr.getSessionId() + "> Failure processing Credit-Control-Request [" + (ccr.getCcRequestType() == CcRequestType.INITIAL_REQUEST ? "INITIAL" : "UPDATE") + "]", e);
			}
			break;
			// TERMINATION_REQUEST 3
		case TERMINATION_REQUEST:
			try {
				if (tracer.isInfoEnabled()) {
					tracer.info("[>>] SID<" + ccr.getSessionId() + "> '" + endUserId + "' requested service termination for '" + serviceContextId + "'.");
				}
				accountBalanceManagement = getAccountManager();
				ratingEngineManagement = getRatingEngineManager();

				for (MultipleServicesCreditControlAvp mscc : ccr.getMultipleServicesCreditControls()) {

					//long[] serviceIds = mscc.getServiceIdentifiers();

					UsedServiceUnitAvp[] usedUnits = mscc.getUsedServiceUnits();
					
					sessionInfo = getSessionInfo();
					CreditControlInfo reservedInfo = sessionInfo.getReservations().get(sessionInfo.getReservations().size() - 1);
					ArrayList<CreditControlUnit> reservedCCUnits = reservedInfo.getCcUnits();

					long usedUnitsInputCount = 0;
					long usedUnitsMoneyCount = 0;
					long usedUnitsOutputCount = 0;
					long usedUnitsServiceSpecificCount = 0;
					long usedUnitsTimeCount = 0;
					long usedUnitsTotalCount = 0;

					ArrayList<CreditControlUnit> usedCCUnits = new ArrayList<CreditControlUnit>();
					for (UsedServiceUnitAvp usedUnit : usedUnits) {
						if (usedUnit.getCreditControlInputOctets() > 0) {
							usedUnitsInputCount += usedUnit.getCreditControlInputOctets();
							CreditControlUnit ccUnit = new CreditControlUnit();
							ccUnit.setUnitType(CcUnitType.INPUT_OCTETS);
							ccUnit.setUsedUnits(usedUnitsInputCount);
							for (int j = 0; j < reservedCCUnits.size(); j++) {
								CreditControlUnit reservedCCUnit = reservedCCUnits.get(j);
								if (reservedCCUnit.getUnitType() == CcUnitType.INPUT_OCTETS) {
									// Copy the reserved amount from the last session into this session so that ABMF can update used units.
									ccUnit.setReservedUnits(reservedCCUnit.getReservedUnits());
									ccUnit.setReservedAmount(reservedCCUnit.getReservedAmount());
									ccUnit.setUsedAmount((long)Math.ceil(reservedCCUnit.getRateForService() * ccUnit.getUsedUnits()));
									ccUnit.setRateForService(reservedCCUnit.getRateForService());
								}
							}
							usedCCUnits.add(ccUnit);
						}
						CcMoneyAvp moneyUnitsTmp = usedUnit.getCreditControlMoneyAvp();
						if (moneyUnitsTmp != null && moneyUnitsTmp.getUnitValue().getValueDigits() > 0){
							usedUnitsMoneyCount += moneyUnitsTmp.getUnitValue().getValueDigits();
							CreditControlUnit ccUnit = new CreditControlUnit();
							ccUnit.setUnitType(CcUnitType.MONEY);
							ccUnit.setUsedUnits(usedUnitsMoneyCount);
							ccUnit.setCcMoney(moneyUnitsTmp);
							for (int j = 0; j < reservedCCUnits.size(); j++) {
								CreditControlUnit reservedCCUnit = reservedCCUnits.get(j);
								if (reservedCCUnit.getUnitType() == CcUnitType.MONEY) {
									// Copy the reserved amount from the last session into this session so that ABMF can update used units.
									ccUnit.setReservedUnits(reservedCCUnit.getReservedUnits());
									ccUnit.setReservedAmount(reservedCCUnit.getReservedAmount());
									ccUnit.setUsedAmount((long)Math.ceil(reservedCCUnit.getRateForService() * ccUnit.getUsedUnits()));
									ccUnit.setRateForService(reservedCCUnit.getRateForService());
								}
							}
							usedCCUnits.add(ccUnit);
						}
						if (usedUnit.getCreditControlOutputOctets() > 0) {
							usedUnitsOutputCount += usedUnit.getCreditControlOutputOctets();
							CreditControlUnit ccUnit = new CreditControlUnit();
							ccUnit.setUnitType(CcUnitType.OUTPUT_OCTETS);
							ccUnit.setUsedUnits(usedUnitsOutputCount);
							for (int j = 0; j < reservedCCUnits.size(); j++) {
								CreditControlUnit reservedCCUnit = reservedCCUnits.get(j);
								if (reservedCCUnit.getUnitType() == CcUnitType.OUTPUT_OCTETS) {
									// Copy the reserved amount from the last session into this session so that ABMF can update used units.
									ccUnit.setReservedUnits(reservedCCUnit.getReservedUnits());
									ccUnit.setReservedAmount(reservedCCUnit.getReservedAmount());
									ccUnit.setUsedAmount((long)Math.ceil(reservedCCUnit.getRateForService() * ccUnit.getUsedUnits()));
									ccUnit.setRateForService(reservedCCUnit.getRateForService());
								}
							}
							usedCCUnits.add(ccUnit);
						}
						if (usedUnit.getCreditControlServiceSpecificUnits() > 0) {
							usedUnitsServiceSpecificCount += usedUnit.getCreditControlServiceSpecificUnits();
							CreditControlUnit ccUnit = new CreditControlUnit();
							ccUnit.setUsedUnits(usedUnitsServiceSpecificCount);
							ccUnit.setUnitType(CcUnitType.SERVICE_SPECIFIC_UNITS);
							for (int j = 0; j < reservedCCUnits.size(); j++) {
								CreditControlUnit reservedCCUnit = reservedCCUnits.get(j);
								if (reservedCCUnit.getUnitType() == CcUnitType.SERVICE_SPECIFIC_UNITS) {
									// Copy the reserved amount from the last session into this session so that ABMF can update used units.
									ccUnit.setReservedUnits(reservedCCUnit.getReservedUnits());
									ccUnit.setReservedAmount(reservedCCUnit.getReservedAmount());
									ccUnit.setUsedAmount((long)Math.ceil(reservedCCUnit.getRateForService() * ccUnit.getUsedUnits()));
									ccUnit.setRateForService(reservedCCUnit.getRateForService());
								}
							}
							usedCCUnits.add(ccUnit);
						}
						if (usedUnit.getCreditControlTime() > 0) {
							usedUnitsTimeCount += usedUnit.getCreditControlTime();
							CreditControlUnit ccUnit = new CreditControlUnit();
							ccUnit.setUnitType(CcUnitType.TIME);
							ccUnit.setUsedUnits(usedUnitsTimeCount);
							for (int j = 0; j < reservedCCUnits.size(); j++) {
								CreditControlUnit reservedCCUnit = reservedCCUnits.get(j);
								if (reservedCCUnit.getUnitType() == CcUnitType.TIME) {
									// Copy the reserved amount from the last session into this session so that ABMF can update used units.
									ccUnit.setReservedUnits(reservedCCUnit.getReservedUnits());
									ccUnit.setReservedAmount(reservedCCUnit.getReservedAmount());
									ccUnit.setUsedAmount((long)Math.ceil(reservedCCUnit.getRateForService() * ccUnit.getUsedUnits()));
									ccUnit.setRateForService(reservedCCUnit.getRateForService());
								}
							}
							usedCCUnits.add(ccUnit);
						}
						if (usedUnit.getCreditControlTotalOctets() > 0) {
							usedUnitsTotalCount += usedUnit.getCreditControlTotalOctets();
							CreditControlUnit ccUnit = new CreditControlUnit();
							ccUnit.setUnitType(CcUnitType.TOTAL_OCTETS);
							ccUnit.setUsedUnits(usedUnitsTotalCount);
							for (int j = 0; j < reservedCCUnits.size(); j++) {
								CreditControlUnit reservedCCUnit = reservedCCUnits.get(j);
								if (reservedCCUnit.getUnitType() == CcUnitType.TOTAL_OCTETS) {
									// Copy the reserved amount from the last session into this session so that ABMF can update used units.
									ccUnit.setReservedUnits(reservedCCUnit.getReservedUnits());
									ccUnit.setReservedAmount(reservedCCUnit.getReservedAmount());
									ccUnit.setUsedAmount((long)Math.ceil(reservedCCUnit.getRateForService() * ccUnit.getUsedUnits()));
									ccUnit.setRateForService(reservedCCUnit.getRateForService());
								}
							}
							usedCCUnits.add(ccUnit);
						}
					}

					// Build Credit Control Info Request to ABMF
					CreditControlInfo ccInfo = new CreditControlInfo();
					ccInfo.setEventTimestamp(System.currentTimeMillis());
					ccInfo.setEventType(ccr.getCcRequestType().toString());
					ccInfo.setRequestNumber((int) ccr.getCcRequestNumber());
					ccInfo.setSessionId(sessionId);
					ccInfo.setSubscriptionId(endUserId);
					ccInfo.setSubscriptionIdType(endUserType);
					ccInfo.setCcUnits(usedCCUnits);

					// Call ABMF with this Credit Control Info 
					accountBalanceManagement.terminateRequest(ccInfo);

					// No need to Store Credit Control Info in CMP. SLEE Container automatically takes care of garbage collection.
					// sessionInfo = getSessionInfo();
					// sessionInfo.getReservations().add(ccInfo);
					// setSessionInfo(sessionInfo);

					return; // we'll continue @ resumeOnCreditControlRequest(..)				
				}

				// 8.7.  Cost-Information AVP
				//
				// The Cost-Information AVP (AVP Code 423) is of type Grouped, and it is
				// used to return the cost information of a service, which the credit-
				// control client can transfer transparently to the end user.  The
				// included Unit-Value AVP contains the cost estimate (always type of
				// money) of the service, in the case of price enquiry, or the
				// accumulated cost estimation, in the case of credit-control session.
				//
				// The Currency-Code specifies in which currency the cost was given.
				// The Cost-Unit specifies the unit when the service cost is a cost per
				// unit (e.g., cost for the service is $1 per minute).
				//
				// When the Requested-Action AVP with value PRICE_ENQUIRY is included in
				// the Credit-Control-Request command, the Cost-Information AVP sent in
				// the succeeding Credit-Control-Answer command contains the cost
				// estimation of the requested service, without any reservation being
				// made.
				//
				// The Cost-Information AVP included in the Credit-Control-Answer
				// command with the CC-Request-Type set to UPDATE_REQUEST contains the
				// accumulated cost estimation for the session, without taking any
				// credit reservation into account.
				//
				// The Cost-Information AVP included in the Credit-Control-Answer
				// command with the CC-Request-Type set to EVENT_REQUEST or
				// TERMINATION_REQUEST contains the estimated total cost for the
				// requested service.
				//
				// It is defined as follows (per the grouped-avp-def of
				// RFC 3588 [DIAMBASE]):
				//
				//           Cost-Information ::= < AVP Header: 423 >
				//                                { Unit-Value }
				//                                { Currency-Code }
				//                                [ Cost-Unit ]

				// 7.2.133 Remaining-Balance AVP
				//
				// The Remaining-Balance AVP (AVPcode 2021) is of type Grouped and
				// provides information about the remaining account balance of the
				// subscriber.
				//
				// It has the following ABNF grammar:
				//      Remaining-Balance :: =  < AVP Header: 2021 >
				//                              { Unit-Value }
				//                              { Currency-Code }

				// We use no money notion ... maybe later.
				// AvpSet costInformation = ccaAvps.addGroupedAvp(423);

				// Answer with DIAMETER_SUCCESS, since "4) The default action for failed operations should be to terminate the data session"
				// its terminated, we cant do much here...
				cca = createCCA(ccServerActivity, ccr, null, DiameterResultCode.DIAMETER_SUCCESS);
				ccServerActivity.sendRoCreditControlAnswer(cca);
			}
			catch (Exception e) {
				tracer.severe("[xx] SID<" + ccr.getSessionId() + "> Failure processing Credit-Control-Request [TERMINATION]", e);
			}
			break;
			// EVENT_REQUEST 4
		case EVENT_REQUEST:
			try {
				if (tracer.isInfoEnabled()) {
					tracer.info("[<<] SID<" + ccr.getSessionId() + "> Received Credit-Control-Request [EVENT]");

					if (tracer.isFineEnabled()) {
						tracer.fine(ccr.toString());
					}
				}
				accountBalanceManagement = getAccountManager();
				ratingEngineManagement = getRatingEngineManager();
				for (MultipleServicesCreditControlAvp mscc : ccr.getMultipleServicesCreditControls()) {
					RequestedServiceUnitAvp rsu = mscc.getRequestedServiceUnit();
					ArrayList<CreditControlUnit> ccUnits = new ArrayList<CreditControlUnit>();
					
					long[] serviceIds = mscc.getServiceIdentifiers();

					long requestedUnits = 0;
					// Input Octets
					requestedUnits = rsu.getCreditControlInputOctets();
					if (requestedUnits > 0) {
						CreditControlUnit ccUnit = new CreditControlUnit();
						ccUnit.setUnitType(CcUnitType.INPUT_OCTETS);
						double rateForService = getRateForService(ccr, ratingEngineManagement, serviceIds[0], CcUnitType.INPUT_OCTETS.getValue(), requestedUnits);
						ccUnit.setRateForService(rateForService);
						ccUnit.setRequestedUnits(requestedUnits);
						ccUnit.setRequestedAmount((long)Math.ceil(requestedUnits * rateForService));
						ccUnits.add(ccUnit);
					}
					// Money 
					CcMoneyAvp moneyUnitsTmp = rsu.getCreditControlMoneyAvp();
					if (moneyUnitsTmp != null) {
						requestedUnits = moneyUnitsTmp.getUnitValue().getValueDigits();
						if (requestedUnits > 0) {
							CreditControlUnit ccUnit = new CreditControlUnit();
							ccUnit.setUnitType(CcUnitType.MONEY);
							double rateForService = getRateForService(ccr, ratingEngineManagement, serviceIds[0],CcUnitType.MONEY.getValue(), requestedUnits);
							ccUnit.setRateForService(rateForService);
							ccUnit.setRequestedUnits(requestedUnits);
							ccUnit.setRequestedAmount((long)Math.ceil(requestedUnits * rateForService));
							ccUnit.setCcMoney(rsu.getCreditControlMoneyAvp());
							ccUnits.add(ccUnit);
						}
					}
					// Output Octets 
					requestedUnits = rsu.getCreditControlOutputOctets();
					if (requestedUnits > 0) {
						CreditControlUnit ccUnit = new CreditControlUnit();
						ccUnit.setUnitType(CcUnitType.OUTPUT_OCTETS);
						double rateForService = getRateForService(ccr, ratingEngineManagement, serviceIds[0], CcUnitType.OUTPUT_OCTETS.getValue(), requestedUnits);
						ccUnit.setRateForService(rateForService);
						ccUnit.setRequestedUnits(requestedUnits);
						ccUnit.setRequestedAmount((long)Math.ceil(requestedUnits * rateForService));
						ccUnits.add(ccUnit);
					}
					// Service Specific Units
					requestedUnits = rsu.getCreditControlServiceSpecificUnits();
					if (requestedUnits > 0) {
						CreditControlUnit ccUnit = new CreditControlUnit();
						ccUnit.setUnitType(CcUnitType.SERVICE_SPECIFIC_UNITS);
						double rateForService = getRateForService(ccr, ratingEngineManagement, serviceIds[0], CcUnitType.SERVICE_SPECIFIC_UNITS.getValue(), requestedUnits);
						ccUnit.setRateForService(rateForService);
						ccUnit.setRequestedUnits(requestedUnits);
						ccUnit.setRequestedAmount((long)Math.ceil(requestedUnits * rateForService));
						ccUnits.add(ccUnit);
					}
					// Time 
					requestedUnits = rsu.getCreditControlTime();
					if (requestedUnits > 0) {
						CreditControlUnit ccUnit = new CreditControlUnit();
						ccUnit.setUnitType(CcUnitType.TIME);
						double rateForService = getRateForService(ccr, ratingEngineManagement, serviceIds[0], CcUnitType.TIME.getValue(), requestedUnits);
						ccUnit.setRateForService(rateForService);
						ccUnit.setRequestedUnits(requestedUnits);
						ccUnit.setRequestedAmount((long)Math.ceil(requestedUnits * rateForService));
						ccUnits.add(ccUnit);
					}
					// Total Octets
					requestedUnits = rsu.getCreditControlTotalOctets();
					if (requestedUnits > 0) {
						CreditControlUnit ccUnit = new CreditControlUnit();
						ccUnit.setUnitType(CcUnitType.TOTAL_OCTETS);
						double rateForService = getRateForService(ccr, ratingEngineManagement, serviceIds[0], CcUnitType.TOTAL_OCTETS.getValue(), requestedUnits);
						ccUnit.setRateForService(rateForService);
						ccUnit.setRequestedUnits(requestedUnits);
						ccUnit.setRequestedAmount((long)Math.ceil(requestedUnits * rateForService));
						ccUnits.add(ccUnit);
					}

					// Build Credit Control Info Request to ABMF
					CreditControlInfo ccInfo = new CreditControlInfo();
					ccInfo.setEventTimestamp(System.currentTimeMillis());
					ccInfo.setEventType(ccr.getCcRequestType().toString());
					ccInfo.setCcUnits(ccUnits);
					ccInfo.setRequestNumber((int) ccr.getCcRequestNumber());
					ccInfo.setSessionId(sessionId);
					ccInfo.setSubscriptionId(endUserId);
					ccInfo.setSubscriptionIdType(endUserType);

					// Call ABMF with this Credit Control Info 
					accountBalanceManagement.eventRequest(ccInfo);

					// Store Credit Control Info in CMP
					sessionInfo = getSessionInfo();
					sessionInfo.setCcr(ccr);
					sessionInfo.setEndUserId(endUserId);
					//sessionInfo.getReservations().add(ccInfo);
					setSessionInfo(sessionInfo);

					if (tracer.isInfoEnabled()) {
						tracer.info(sessionInfo.toString());
					}			

					return; // we'll continue @ resumeOnCreditControlRequest(..)
				}

				aci.detach(this.getSbbContext().getSbbLocalObject());
			}
			catch (Exception e) {
				tracer.severe("[xx] SID<" + ccr.getSessionId() + "> Failure processing Credit-Control-Request [EVENT]", e);
			}
			break;
		default:
			tracer.warning("[xx] SID<" + ccr.getSessionId() + "> Unknown request type found!");
			break;
		}
	}

	public void onTimerEvent(TimerEvent timer, ActivityContextInterface aci) {
		// detach from this activity, we don't want to handle any other event on it
		aci.detach(this.sbbContextExt.getSbbLocalObject());
		tracer.info("[--] Terminating Activity " + aci.getActivity());
		((RoServerSessionActivity) aci.getActivity()).endActivity();
	}

	/**
	 * @param ccServerActivity
	 * @param request
	 * @param reservations
	 * @param resultCode
	 * @return
	 */
	private RoCreditControlAnswer createCCA(RoServerSessionActivity ccServerActivity, RoCreditControlRequest request, List<CreditControlInfo> reservations, long resultCode) {
		RoCreditControlAnswer answer = ccServerActivity.createRoCreditControlAnswer();

		// <Credit-Control-Answer> ::= < Diameter Header: 272, PXY >
		//  < Session-Id >
		//  { Result-Code }
		answer.setResultCode(resultCode);
		//  { Origin-Host }
		//  { Origin-Realm }
		//  { Auth-Application-Id }

		//  { CC-Request-Type }
		// Using the same as the one present in request
		answer.setCcRequestType(request.getCcRequestType());

		//  { CC-Request-Number }
		// Using the same as the one present in request
		answer.setCcRequestNumber(request.getCcRequestNumber());

		//  [ User-Name ]
		//  [ CC-Session-Failover ]
		//  [ CC-Sub-Session-Id ]
		//  [ Acct-Multi-Session-Id ]
		//  [ Origin-State-Id ]
		//  [ Event-Timestamp ]

		//  [ Granted-Service-Unit ]
		//
		// 8.17.  Granted-Service-Unit AVP
		//
		// Granted-Service-Unit AVP (AVP Code 431) is of type Grouped and
		// contains the amount of units that the Diameter credit-control client
		// can provide to the end user until the service must be released or the
		// new Credit-Control-Request must be sent.  A client is not required to
		// implement all the unit types, and it must treat unknown or
		// unsupported unit types in the answer message as an incorrect CCA
		// answer.  In this case, the client MUST terminate the credit-control
		// session and indicate in the Termination-Cause AVP reason
		// DIAMETER_BAD_ANSWER.
		//
		// The Granted-Service-Unit AVP is defined as follows (per the grouped-
		// avp-def of RFC 3588 [DIAMBASE]):
		//
		// Granted-Service-Unit ::= < AVP Header: 431 >
		//                          [ Tariff-Time-Change ]
		//                          [ CC-Time ]
		//                          [ CC-Money ]
		//                          [ CC-Total-Octets ]
		//                          [ CC-Input-Octets ]
		//                          [ CC-Output-Octets ]
		//                          [ CC-Service-Specific-Units ]
		//                         *[ AVP ]
		if (reservations != null && reservations.size() > 0) {
			MultipleServicesCreditControlAvp[] reqMSCCs = request.getMultipleServicesCreditControls();
			List<MultipleServicesCreditControlAvp> ansMSCCs = new ArrayList<MultipleServicesCreditControlAvp>();
			for (int index = 0; index < reqMSCCs.length; index++) {
				MultipleServicesCreditControlAvp reqMSCC = reqMSCCs[index];
				MultipleServicesCreditControlAvp ansMscc = avpFactory.createMultipleServicesCreditControl();
				ansMscc.setRatingGroup(reqMSCC.getRatingGroup());
				ansMscc.setServiceIdentifiers(reqMSCC.getServiceIdentifiers());
				CreditControlInfo ccInfo = reservations.get(index);
				if (ccInfo.isSuccessful()) {
					GrantedServiceUnitAvp gsu = avpFactory.createGrantedServiceUnit();
					ArrayList<CreditControlUnit> ccUnits = ccInfo.getCcUnits();
					for (int i = 0; i < ccUnits.size(); i++) {
						CreditControlUnit ccUnit = ccUnits.get(i);
						if (ccUnit.getUnitType() == CcUnitType.INPUT_OCTETS) {
							gsu.setCreditControlInputOctets(ccUnit.getReservedUnits());
						}
						if (ccUnit.getUnitType() == CcUnitType.MONEY) {
							gsu.setCreditControlMoneyAvp(ccUnit.getCcMoney());
						}
						if (ccUnit.getUnitType() == CcUnitType.OUTPUT_OCTETS) {
							gsu.setCreditControlOutputOctets(ccUnit.getReservedUnits());
						}
						if (ccUnit.getUnitType() == CcUnitType.SERVICE_SPECIFIC_UNITS) {
							gsu.setCreditControlServiceSpecificUnits(ccUnit.getReservedUnits());
						}
						if (ccUnit.getUnitType() == CcUnitType.TIME) {
							gsu.setCreditControlTime(ccUnit.getReservedUnits());
						}
						if (ccUnit.getUnitType() == CcUnitType.TOTAL_OCTETS) {
							gsu.setCreditControlTotalOctets(ccUnit.getReservedUnits());
						}
					}
					ansMscc.setGrantedServiceUnit(gsu);
					ansMscc.setResultCode(DiameterResultCode.DIAMETER_SUCCESS);

					// TODO: Have Final-Unit-Indication when needed...
					// If we are terminating gracefully we MAY include the Final-Unit-Indication
					if (answer.getCcRequestType() == CcRequestType.TERMINATION_REQUEST) {
						FinalUnitIndicationAvp fuiAvp = avpFactory.createFinalUnitIndication();
						fuiAvp.setFinalUnitAction(FinalUnitActionType.TERMINATE);
						ansMscc.setFinalUnitIndication(fuiAvp);
					}
				}
				else {
					// In case it's not successful we want to have Final-Unit-Indication
					FinalUnitIndicationAvp fuiAvp = avpFactory.createFinalUnitIndication();
					fuiAvp.setFinalUnitAction(FinalUnitActionType.TERMINATE);
					ansMscc.setFinalUnitIndication(fuiAvp);

					ansMscc.setResultCode(resultCode);
				}
				ansMSCCs.add(ansMscc);
				ansMscc.setValidityTime(DEFAULT_VALIDITY_TIME);
			}
			answer.setMultipleServicesCreditControls(ansMSCCs.toArray(new MultipleServicesCreditControlAvp[ansMSCCs.size()]));
		}

		// *[ Multiple-Services-Credit-Control ]
		//  [ Cost-Information]
		//  [ Final-Unit-Indication ]
		//  [ Check-Balance-Result ]
		//  [ Credit-Control-Failure-Handling ]
		//  [ Direct-Debiting-Failure-Handling ]
		//  [ Validity-Time]
		//Ro does not use message level VT
		// *[ Redirect-Host]
		//  [ Redirect-Host-Usage ]
		//  [ Redirect-Max-Cache-Time ]
		// *[ Proxy-Info ]
		// *[ Route-Record ]
		// *[ Failed-AVP ]
		// *[ AVP ]

		if (tracer.isInfoEnabled()) {
			tracer.info("[>>] SID<" + request.getSessionId() + "> Created Credit-Control-Answer with Result-Code = " + answer.getResultCode() + ".");
			if (tracer.isFineEnabled()) {
				tracer.fine(answer.toString());
			}
		}

		return answer;
	}

	/**
	 * Sends the Credit-Control-Answer through the ACI and detaches if set to.
	 * @param cca the Credit-Control-Answer to send
	 * @param aci the ACI where to send from
	 * @param detach boolean indicating whether to detach or not
	 * @return true if it succeeds sending, false otherwise
	 */
	private boolean sendCCA(RoCreditControlAnswer cca, ActivityContextInterface aci, boolean detach) {
		try {
			RoServerSessionActivity ccServerActivity = (RoServerSessionActivity) aci.getActivity();
			ccServerActivity.sendRoCreditControlAnswer(cca);
			if (detach) {
				aci.detach(this.getSbbContext().getSbbLocalObject());
			}
			return true;
		}
		catch (IOException e) {
			tracer.severe("[xx] SID<" + cca.getSessionId() + "> Error while trying to send Credit-Control-Answer.", e);
			return false;
		}
	}

	//private String storedEndUserId;
	//private long storedRequestedUnits;
	//private long[] storedServiceIds;
	//private ArrayList<UnitReservation> storedReservations = new ArrayList<UnitReservation>();

	@Override
	public void resumeOnCreditControlRequest(CreditControlInfo ccInfo) {
		UserSessionInfo sessionInfo = getSessionInfo();
		RoCreditControlRequest storedCCR = sessionInfo.getCcr();
		if (tracer.isInfoEnabled()) {
			tracer.info("[<<] SID<" + storedCCR.getSessionId() + "> Resuming Handling of Credit-Control-Request [" + storedCCR.getCcRequestType().toString() + "]");
		}

		sessionInfo.getReservations().add(ccInfo);
		setSessionInfo(sessionInfo);
		long resultCode = DiameterResultCode.DIAMETER_SUCCESS;
		if (ccInfo.isSuccessful()) {
			if (tracer.isInfoEnabled()) {
				tracer.info("[>>] SID<" + storedCCR.getSessionId() + "> '" + sessionInfo.getEndUserId() + "' GRANTED for '" + Arrays.toString(sessionInfo.getServiceIds()) + "'.");
			}
		}
		else {
			if (tracer.isInfoEnabled()) {
				tracer.info("[>>] SID<" + storedCCR.getSessionId() + "> '" + sessionInfo.getEndUserId() + "' DENIED for '" + Arrays.toString(sessionInfo.getServiceIds()) + "'.");
			}
			// If we can't determine error, say UNABLE_TO_COMPLY
			resultCode = ccInfo.getErrorCodeType() != null ? getResultCode(ccInfo.getErrorCodeType()) : DiameterResultCode.DIAMETER_UNABLE_TO_COMPLY;
		}

		try {
			RoServerSessionActivity activity = getServerSessionActivityToReply(storedCCR.getCcRequestType() == CcRequestType.TERMINATION_REQUEST || storedCCR.getCcRequestType() == CcRequestType.EVENT_REQUEST);
			RoCreditControlAnswer cca = sessionInfo.getReservations().size() > 0 ? createCCA(activity, storedCCR, sessionInfo.getReservations(), resultCode) : createCCA(activity, storedCCR, null, DiameterResultCode.DIAMETER_MISSING_AVP);
			activity.sendRoCreditControlAnswer(cca);

			// Output the user session details.
			if (tracer.isInfoEnabled()) {
				tracer.info("CCA successfully sent. Dumping session info...\n" + sessionInfo);
			}
		}
		catch (Exception e) {
			tracer.severe("[xx] Unable to send Credit-Control-Answer.", e);
		}


		if (storedCCR.getCcRequestType() == CcRequestType.TERMINATION_REQUEST) {
			if (tracer.isInfoEnabled()) {
				tracer.info("[>>] Generating CDR for SessionId '" + storedCCR.getSessionId() + "'...");
			}

			// Let's sum up the total used units and total used amount for the CDR.
			ArrayList<CreditControlInfo> reserv = sessionInfo.getReservations(); 

			long balanceBefore = 0;
			long balanceAfter = 0;
			long totalUsedUnitsInput = 0;
			long totalUsedUnitsMoney = 0;
			long totalUsedUnitsOutput = 0;
			long totalUsedUnitsServiceSpecific = 0;
			long totalUsedUnitsTime = 0;
			long totalUsedUnitsTotal = 0;
			long totalUsedAmountInput = 0;
			long totalUsedAmountMoney = 0;
			long totalUsedAmountOutput = 0;
			long totalUsedAmountServiceSpecific = 0;
			long totalUsedAmountTime = 0;
			long totalUsedAmountTotal = 0;
			
			for (int i = 0; i < reserv.size(); i++) {
				CreditControlInfo ccI = reserv.get(i);
				
				ArrayList<CreditControlUnit> ccUnits = ccI.getCcUnits();
				for (int j = 0; j < ccUnits.size(); j++) {
					CreditControlUnit ccUnit = ccUnits.get(j);
					if (ccUnit.getUnitType() == CcUnitType.INPUT_OCTETS) {
						totalUsedUnitsInput += ccUnit.getUsedUnits();
						totalUsedAmountInput += ccUnit.getUsedAmount();
					}
					if (ccUnit.getUnitType() == CcUnitType.MONEY) {
						totalUsedUnitsMoney += ccUnit.getUsedUnits();
						totalUsedAmountMoney += ccUnit.getUsedAmount();
					}
					if (ccUnit.getUnitType()==CcUnitType.OUTPUT_OCTETS) {
						totalUsedUnitsOutput += ccUnit.getUsedUnits();
						totalUsedAmountOutput += ccUnit.getUsedAmount();
					}
					if (ccUnit.getUnitType()==CcUnitType.SERVICE_SPECIFIC_UNITS) {
						totalUsedUnitsServiceSpecific += ccUnit.getUsedUnits();
						totalUsedAmountServiceSpecific += ccUnit.getUsedAmount();
					}
					if (ccUnit.getUnitType()==CcUnitType.TIME) {
						totalUsedUnitsTime += ccUnit.getUsedUnits();
						totalUsedAmountTime += ccUnit.getUsedAmount();
					}
					if (ccUnit.getUnitType()==CcUnitType.TOTAL_OCTETS) {
						totalUsedUnitsTotal += ccUnit.getUsedUnits();
						totalUsedAmountTotal += ccUnit.getUsedAmount();
					}
				}

				if (i == 0) {
					balanceBefore = ccI.getBalanceBefore();
				}
				if (i == reserv.size() - 1) {
					balanceAfter = ccI.getBalanceAfter();
				}
			}


			/**
			 * Date Time of record (Format: yyyy-MM-dd'T'HH:mm:ss.SSSZ)
			 * Diameter Origin Host
			 * Diameter Origin Realm
			 * Diameter Destination Host
			 * Diameter Destination Realm
			 * Service IDs
			 * Session Start Time
			 * Current Time in milliseconds
			 * Session Duration
			 * SessionID
			 * Calling party type
			 * Calling party info
			 * Called party type
			 * Called party info
			 * Balance Before
			 * Balance After
			 * Total Input Octets Units Used
			 * Total Input Octets Amount Charged
			 * Total Money Units Used
			 * Total Money Amount Charged
			 * Total Output Octets Units Used
			 * Total Output Octets Amount Charged
			 * Total Service Specific Units Used
			 * Total Service Specific Amount Charged
			 * Total Time Units Used
			 * Total Time Amount Charged
			 * Total Total Octets Units Used
			 * Total Total Octets Amount Charged
			 * Event Type - Create/Interim/Terminate/Event (CDR's are only generated at Terminate for now)
			 * Number of events in this session
			 * Termination Cause 
			 **/
			String CDR = "";
			String DELIMITER = ";";

			SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
			long elapsed = System.currentTimeMillis()-sessionInfo.getSessionStartTime();

			CDR += df.format(new Date()) 							+ DELIMITER;
			CDR += sessionInfo.getCcr().getOriginHost()				+ DELIMITER;
			CDR += sessionInfo.getCcr().getOriginRealm()			+ DELIMITER;
			CDR += sessionInfo.getCcr().getDestinationHost()		+ DELIMITER;
			CDR += sessionInfo.getCcr().getDestinationRealm()		+ DELIMITER;
			CDR += Arrays.toString(sessionInfo.getServiceIds())		+ DELIMITER;
			CDR += sessionInfo.getSessionStartTime()				+ DELIMITER;
			CDR += System.currentTimeMillis()					 	+ DELIMITER;
			CDR += elapsed										 	+ DELIMITER;
			CDR += sessionInfo.getCcr().getSessionId() 				+ DELIMITER;
			CDR += sessionInfo.getEndUserType().getValue()			+ DELIMITER;
			CDR += sessionInfo.getEndUserId()						+ DELIMITER;
			// TODO: Get Destination Subscription ID Type and Value if available
			CDR += sessionInfo.getEndUserType().getValue()			+ DELIMITER;
			CDR += sessionInfo.getEndUserId()						+ DELIMITER;
			CDR += balanceBefore									+ DELIMITER;
			CDR += balanceAfter										+ DELIMITER;
			CDR += totalUsedUnitsInput								+ DELIMITER;
			CDR += totalUsedAmountInput								+ DELIMITER;
			CDR += totalUsedUnitsMoney								+ DELIMITER;
			CDR += totalUsedAmountMoney								+ DELIMITER;
			CDR += totalUsedUnitsOutput								+ DELIMITER;
			CDR += totalUsedAmountOutput							+ DELIMITER;
			CDR += totalUsedUnitsServiceSpecific					+ DELIMITER;
			CDR += totalUsedAmountServiceSpecific					+ DELIMITER;
			CDR += totalUsedUnitsTime								+ DELIMITER;
			CDR += totalUsedAmountTime								+ DELIMITER;
			CDR += totalUsedUnitsTotal								+ DELIMITER;
			CDR += totalUsedAmountTotal								+ DELIMITER;
			CDR += storedCCR.getCcRequestType().getValue()			+ DELIMITER;
			CDR += sessionInfo.getReservations().size()				+ DELIMITER;
			CDR += storedCCR.getTerminationCause()					+ DELIMITER;
			// TODO: Use a different logger.
			tracer.info(CDR);
		}
	}

	@Override
	public void updateAccountDataResult(boolean success) {
		if (success) {
			if (tracer.isInfoEnabled()) {
				tracer.info("[><] Update User Account Data completed with success.");
			}			
		}
		else {
			tracer.warning("[><] Update User Account Data failed.");
		}
	}

	private RoServerSessionActivity getServerSessionActivityToReply(boolean detach) {
		ActivityContextInterface[] acis = this.sbbContextExt.getActivities();
		Object activity = null;
		for (ActivityContextInterface aci : acis) {
			activity = aci.getActivity();
			if (activity instanceof RoServerSessionActivity) {
				// detach to not handle the activity end
				if (detach) {
					aci.detach(sbbContextExt.getSbbLocalObject());
				}
				return (RoServerSessionActivity) activity;
			}
		}
		return null;
	}

	// --------- Call to decentralized rating engine ---------------------
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private double getRateForService(RoCreditControlRequest ccr, RatingEngineClient ratingEngineManagement, long serviceId, long unitTypeId, long requestedUnits) {

		// Let's make some variables available to be sent to the rating engine
		String sessionId = ccr.getSessionId();
		String myHost = ccr.getDestinationHost().toString();
		String requestType = ccr.getCcRequestType().toString();
		long ccrTimeStamp = ccr.getEventTimestamp().getTime();
		long currentTimeStamp = Calendar.getInstance().getTimeInMillis();
		int callingPartyType = -1;
		String callingParty = null;

		SubscriptionIdAvp[] subscriptionIds = ccr.getSubscriptionIds();
		if (subscriptionIds != null && subscriptionIds.length > 0) {
			callingPartyType = subscriptionIds[0].getSubscriptionIdType().getValue();
			callingParty = subscriptionIds[0].getSubscriptionIdData();
		}

		HashMap params = new HashMap();
		params.put("ChargingServerHost", myHost);
		params.put("SessionId", sessionId);
		params.put("RequestType", requestType);
		params.put("SubscriptionIdType", callingPartyType);
		params.put("SubscriptionIdData", callingParty);
		//params.put("UnitId", getUnitId((int)serviceId));
		params.put("UnitTypeId", unitTypeId);
		params.put("UnitValue", requestedUnits);
		params.put("ServiceId", serviceId);
		params.put("BeginTime", ccrTimeStamp);
		params.put("ActualTime", currentTimeStamp);

		// TODO: Extract DestinationId AVP from the CCR if available.
		params.put("DestinationIdType", "?");
		params.put("DestinationIdData", "?");

		RatingInfo ratingInfo = ratingEngineManagement.getRateForService(params);

		// Retrieve the rating information [and optionally the unit type] from ratingInfo.

		int responseCode = ratingInfo.getResponseCode();
		double rate = 0;
		if (responseCode == 0) {
			// Rate obtained successfully from Rating Engine, let's use that.
			rate = ratingInfo.getRate();
		}
		else {
			// TODO: if rate was not found or error occurred while determining rate, what to do? Block traffic (certain types of traffic? for certain profiles? Allow for Free?)
			tracer.warning("[xx] Unexpected response code '" + responseCode + "' received from Rating Engine.");
		}

		// allow traffic for free :(
		return rate;
	}

	@Override
	public void getRateForServiceResult(RatingInfo ratingInfo) {
		tracer.info("[><] Got Rate for Service: " + ratingInfo);
	}

	// TODO: Ok, so let's not use this for now (the serviceid-units csv mapping file). Why?
	// According to 3GPP ref, it is not possible to have a decentralized rating engine and centralized unit
	// determination logic.
	//
	// 5.2.2	Charging Scenarios
	// In order to perform event charging via Ro, the scenarios between the involved entities UE-A, OCF and CTF need to
	// be defined. The charging flows shown in this subclause include scenarios with immediate event charging and event
	// charging with reservation. In particular, the following cases are shown:

	//	1	Immediate Event Charging
	//	a)	Decentralized Unit Determination and Centralized Rating
	//	b)	Centralized Unit Determination and Centralized Rating
	//	c)	Decentralized Unit Determination and Decentralized Rating

	//	2	Event charging with Reservation 
	//	a)	Decentralized Unit Determination and Centralized Rating
	//	b)	Centralized Unit Determination and Centralized Rating
	//	c)	Decentralized Unit Determination and Decentralized Rating

	//	3	Session charging with Reservation
	//	a)	Decentralized Unit Determination and Centralized Rating
	//	b)	Centralized Unit Determination and Centralized Rating
	//	c)	Decentralized Unit Determination and Decentralized Rating

	//	The combination of Centralized Unit Determination with Decentralized Rating is not possible.

	/*
	private int getUnitId(int serviceId) {
		int unitId = 0;
		if (serviceIdUnits != null) {
			try {
				unitId = Integer.valueOf(serviceIdUnits.get((int) serviceId).toString());
			}
			catch (Exception e) {
				tracer.warning("Could not get UnitId for ServiceId " + serviceId + ", serviceIdUnits=" + serviceIdUnits, e);
			}
		}
		else {
			tracer.warning("Could not get UnitId for ServiceId " + serviceId);
		}
		return unitId;
	}
	*/

	/**
	 * Convert IP4 address to String
	 * @param address byte array
	 * @return String
	 */
	private String byteArrayToStringIp(byte[] address) {
		if(address == null || address.length != 4) {
			return "0.0.0.0";
		}
		String stringIp = "";
		for (byte number:address) {
			stringIp+=(number & 0xFF) + ".";
		}
		return stringIp.substring(0, stringIp.length()-1);
	}

	/**
	 * Fetch data from AVP to be passed in CreditControlInfo, as configured in env entry.
	 * @param avp the AVP to look at
	 * @param ccInfo the CreditControlInfo object to store properties at
	 */
	private void fetchDataFromAvp(DiameterAvp avp, CreditControlInfo ccInfo) {
		fetchDataFromAvp(avp, ccInfo, 0);
	}

	/**
	 * Fetch data from AVP to be passed in CreditControlInfo, as configured in env entry.
	 * @param avp the AVP to look at
	 * @param ccInfo the CreditControlInfo object to store properties at
	 * @param depth the AVP depth, for recursive calls
	 */
	private void fetchDataFromAvp(DiameterAvp avp, CreditControlInfo ccInfo, int depth) {
		if (tracer.isFineEnabled()) {
			tracer.fine("[><] Scanning AVP at depth " + depth + " with code " + avp.getCode() + " and type " + avp.getType() + " ...");
		}
		if(avp.getType() == DiameterAvpType.GROUPED) {
			GroupedAvp gAvp = (GroupedAvp) avp;
			DiameterAvp[] subAvps = gAvp.getExtensionAvps();
			for(DiameterAvp subAvp : subAvps) {
				fetchDataFromAvp(subAvp, ccInfo, depth+1);
			}
		}
		else {
			String name = abmfAVPs.get(String.valueOf(avp.getCode()));
			if (name != null) {
				Object value = null;
				switch (avp.getType().getType())
				{
					case DiameterAvpType._ADDRESS:
					case DiameterAvpType._DIAMETER_IDENTITY:
					case DiameterAvpType._DIAMETER_URI:
					case DiameterAvpType._IP_FILTER_RULE:
					case DiameterAvpType._OCTET_STRING:
					case DiameterAvpType._QOS_FILTER_RULE:
						value = avp.octetStringValue();
						break;
					case DiameterAvpType._ENUMERATED:
					case DiameterAvpType._INTEGER_32:
						value = avp.intValue();
						break;
					case DiameterAvpType._FLOAT_32:
						value = avp.floatValue();
						break;
					case DiameterAvpType._FLOAT_64:
						value = avp.doubleValue();
						break;
					case DiameterAvpType._INTEGER_64:
						value = avp.longValue();
						break;
					case DiameterAvpType._TIME:
						value = avp.longValue();
						break;
					case DiameterAvpType._UNSIGNED_32:
						value = avp.longValue();
						break;
					case DiameterAvpType._UNSIGNED_64:
						value = avp.longValue();
						break;
					case DiameterAvpType._UTF8_STRING:
						value = avp.octetStringValue();
						break;
					default:
						value = avp.byteArrayValue();
						break;
				}
				if (tracer.isFineEnabled()) {
					tracer.info("[><] Storing AVP with code " + avp.getCode() + " as '" + name + "' with value '" + value.toString() + "'");
				}
				ccInfo.addServiceInfo(name, value.toString());
			}
		}
	}

	// 'sessionInfo' CMP field setter
	public abstract void setSessionInfo(UserSessionInfo value);

	// 'sessionInfo' CMP field getter
	public abstract UserSessionInfo getSessionInfo();
}