package gov.ca.water.calgui.bus_service.impl;

//! Base DSS file access service
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.Vector;

import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JOptionPane;

import org.apache.log4j.Logger;
import org.swixml.SwingEngine;

import calsim.app.Project;
import gov.ca.water.calgui.bo.GUILinks3BO;
import gov.ca.water.calgui.bo.RBListItemBO;
import gov.ca.water.calgui.bo.ResultUtilsBO;
import gov.ca.water.calgui.bus_service.IDSSGrabber1Svc;
import gov.ca.water.calgui.bus_service.ISeedDataSvc;
import gov.ca.water.calgui.constant.Constant;
import gov.ca.water.calgui.tech_service.IDialogSvc;
import gov.ca.water.calgui.tech_service.IErrorHandlingSvc;
import gov.ca.water.calgui.tech_service.impl.DialogSvcImpl;
import gov.ca.water.calgui.tech_service.impl.ErrorHandlingSvcImpl;
import hec.heclib.dss.HecDss;
import hec.heclib.util.HecTime;
import hec.io.TimeSeriesContainer;

/**
 * Class to grab (load) DSS time series for a set of scenarios passed in a
 * JList. Each scenario is a string that corresponds to a fully qualified (?)
 * DSS file name. The DSS_Grabber instance provides access to the following:
 * <ul>
 * <li>Main time series (result, including necessary math) for each scenario<br>
 * <li>Secondary series (control) where indicated for each scenario<br>
 * <li>Difference for main time series for each scenario<br>
 * <li>Exceedance for main time series for each scenario<br>
 * </ul>
 * Typical usage sequence:
 * <ul>
 * <li>DSS_Grabber</li>
 * <li>setIsCFS</li>
 * <li>setBase</li>
 * <li>setLocation</li>
 * <li>setDateRange</li>
 * <li>getPrimarySeries</li>
 * <li>getSecondarySeries</li>
 * <li>Other calculations</li>
 * </ul>
 */
public class DSSGrabber1SvcImpl implements IDSSGrabber1Svc {

	private ISeedDataSvc seedDataSvc = SeedDataSvcImpl.getSeedDataSvcImplInstance();
	private SwingEngine swingEngine = XMLParsingSvcImpl.getXMLParsingSvcImplInstance().getSwingEngine();

	static Logger LOG = Logger.getLogger(DSSGrabber1SvcImpl.class.getName());
	private IErrorHandlingSvc errorHandlingSvc = new ErrorHandlingSvcImpl();
	private IDialogSvc dialogSvc = DialogSvcImpl.getDialogSvcInstance();

	static final double CFS_2_TAF_DAY = 0.001983471;
	static final double TAF_DAY_2_CFS = 504.166667;

	protected final JList lstScenarios;

	protected String baseName;
	protected String primaryDSSName;
	protected String secondaryDSSName;

	protected String title; // Chart title
	protected String yLabel; // Y-axis label
	protected String sLabel; // Label for secondary time series

	protected boolean isCFS; // Indicates whether "CFS" button was selected
	protected String originalUnits; // Copy of original units

	protected int startTime; // Start and end time of interest
	protected int endTime;

	protected int startWY; // USGS Water Year for start and end time.
	protected int endWY;

	protected int scenarios; // Number of scenarios passed in list parameter
	protected double[][] annualTAFs;
	protected double[][] annualTAFsDiff;
	protected double[][] annualCFSs;
	protected double[][] annualCFSsDiff;

	protected Project project = ResultUtilsBO.getResultUtilsInstance(null).getProject();

	private boolean stopOnMissing = true;
	private List<String> missingDSSRecords = new ArrayList<String>();
	private Properties properties = new Properties();

	public DSSGrabber1SvcImpl(JList list) {

		this.lstScenarios = list;
		try {
			properties.load(ModelRunSvcImpl.class.getClassLoader().getResourceAsStream("callite-gui.properties"));
			stopOnMissing = Boolean.parseBoolean(properties.getProperty("stop.display.on.null"));
		} catch (Exception e) {
			stopOnMissing = true;
		}
		clearMissingList();
	}

	/**
	 * Clears list of DSS records that were not found in scenario DV.DSS files
	 */
	public void clearMissingList() {
		missingDSSRecords.clear();
	}

	/**
	 * Provide access to list of DSS records not found during processing
	 * 
	 * @return list, or null if not tracked due to property setting
	 */
	public List<String> getMissingList() {
		return missingDSSRecords;
	}

	/**
	 * Provide access to stopOnMissing flag read from callite-gui.properties
	 * 
	 * @return true = stop display task when missing a record, false = continue
	 *         with task
	 */
	public boolean getStopOnMissing() {
		return stopOnMissing;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#setIsCFS(boolean)
	 */
	@Override
	public void setIsCFS(boolean isCFS) {
		this.isCFS = isCFS;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#setDateRange(java.
	 * lang.String)
	 */
	@Override
	public void setDateRange(String dateRange) {

		try {
			HecTime ht = new HecTime();

			int m = ResultUtilsBO.getResultUtilsInstance(null).monthToInt(dateRange.substring(0, 3));
			int y = new Integer(dateRange.substring(3, 7));
			ht.setYearMonthDay(m == 12 ? y + 1 : y, m == 12 ? 1 : m + 1, 1, 0);
			startTime = ht.value();
			startWY = (m < 10) ? y : y + 1; // Water year

			m = ResultUtilsBO.getResultUtilsInstance(null).monthToInt(dateRange.substring(8, 11));
			y = new Integer(dateRange.substring(11, 15));
			ht.setYearMonthDay(m == 12 ? y + 1 : y, m == 12 ? 1 : m + 1, 1, 0);
			endTime = ht.value();
			endWY = ((m < 10) ? y : y + 1);
		} catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
			errorHandlingSvc.systemErrorHandler("Possible javaheclib.dll issue - CalLite GUI will close",
					"javaHecLib.dll may be the wrong version or missing.", null);
		} catch (Exception e) {

			startTime = -1;
			LOG.debug(e.getMessage());
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#setBase(java.lang.
	 * String)
	 */
	@Override
	public void setBase(String baseName) {

		this.baseName = baseName;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#getBase()
	 */
	@Override
	public String getBase() {

		String delimiter;

		// Windows
		if (baseName.contains("\\")) {

			delimiter = "\\\\";
		}

		// The rest of the world
		else {
			delimiter = "/";
		}

		String[] pathParts = baseName.split(delimiter);
		String fullFileName = pathParts[pathParts.length - 1];
		String fileName = fullFileName.substring(0, fullFileName.lastIndexOf("."));
		return fileName;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#setLocation(java.
	 * lang.String)
	 */
	@Override
	public void setLocation(String locationName) {

		locationName = locationName.trim();

		if (locationName.startsWith("/")) {
			// Handle names passed from WRIMS GUI
			String parts[] = locationName.split("/");
			title = locationName;
			primaryDSSName = parts[2] + "/" + parts[3] + "/" + parts[6];
			secondaryDSSName = "";
			yLabel = "";
			sLabel = "";
		} else {
			String lookupID = locationName;
			if (lookupID.startsWith(Constant.SCHEMATIC_PREFIX))
				// Strip off prefix for schematic view - NOT SURE IF WE CAN'T
				// JUST ELIMINATE PREFIX?
				lookupID = lookupID.substring(Constant.SCHEMATIC_PREFIX.length());

			// Location name is otherwise assumed coded as "ckpbxxx"

			GUILinks3BO guiLinks3BO = seedDataSvc.getObjById(locationName);
			if (guiLinks3BO != null) {
				primaryDSSName = guiLinks3BO.getPrimary();
				secondaryDSSName = guiLinks3BO.getSecondary();
				yLabel = guiLinks3BO.getyTitle();
				title = guiLinks3BO.getTitle();
				sLabel = guiLinks3BO.getyTitle2();
			}
		}
	}

	/**
	 * Provides element type (DSS D-PART) based on prefix location (C-PART). For
	 * example, a C-PART prefix of "S_" maps to "STORAGE".
	 * 
	 * @param name
	 *            (C-PART)
	 * @return appropriate D-PART
	 */
	private String getType(String name) {
		String type;
		if (name.startsWith("S_") || name.startsWith("s_")) {
			type = "STORAGE";
		} else if (name.startsWith("C_") || name.startsWith("c_")) {
			type = "FLOW-CHANNEL";
		} else if (name.startsWith("D_") || name.startsWith("d_")) {
			type = "FLOW-DELIVERY";
		} else if (name.startsWith("R_") || name.startsWith("r_")) {
			type = "RETURN-FLOW";
		} else if (name.startsWith("I_") || name.startsWith("i_")) {
			type = "INFLOW";
		} else if (name.startsWith("AD_") || name.startsWith("ad_")) {
			type = "FLOW-ACCRDEPL";
		} else if (name.startsWith("S") || name.startsWith("s")) {
			type = "STORAGE";
		} else if (name.startsWith("D") || name.startsWith("d")) {
			type = "FLOW-DELIVERY";
		} else if (name.startsWith("C") || name.startsWith("c")) {
			type = "FLOW-CHANNEL";
		} else {
			type = "";
		}
		return type;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#setLocationWeb(java.
	 * lang.String)
	 */
	@Override
	public void setLocationWeb(String locationName) {

		String type = getType(locationName);
		primaryDSSName = locationName + "/" + type;
		secondaryDSSName = "";
		yLabel = type;
		title = locationName;
		sLabel = "";
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#getYLabel()
	 */
	@Override
	public String getYLabel() {
		return yLabel;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#getSLabel()
	 */
	@Override
	public String getSLabel() {
		return sLabel;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#getTitle()
	 */
	@Override
	public String getTitle() {
		if (title != "")
			return title;
		else {
			return primaryDSSName;
		}
	}

	/**
	 * Determines if the cls (CalLite scenario) file associated with a given
	 * DV.DSS file shows dynamic SJR on
	 *
	 * @param dvFilename
	 * @return true if .cls shows dynamic SJR on, false otherwise
	 */
	private boolean clsIsDynamicSJR(String dvFilename) {
		String clsFileName = dvFilename.substring(0, dvFilename.length() - 7) + ".cls";
		File clsF = new File(clsFileName);
		String sjrState = "";
		try {
			Scanner scanner;
			scanner = new Scanner(new FileInputStream(clsF.getAbsolutePath()));
			while (scanner.hasNextLine() && sjrState.equals("")) {
				String text = scanner.nextLine();
				if (text.startsWith("Dynamic_SJR|")) {

					String[] texts = text.split("[|]");
					sjrState = texts[1];
				}
			}
			scanner.close();

		} catch (IOException e) {
			LOG.info(clsF.getName() + " not openable - SJR assumed static");
		}

		return (sjrState.equals("true"));
	}

	/**
	 * Determines if the cls (CalLite scenario) file associated with a given
	 * DV.DSS file shows D1485 Fish and Wildlife (Antioch+Chipps) on
	 *
	 * @param dvFilename
	 * @return true if .cls shows Antioch/Chipps on, false otherwise
	 */
	// Logic to display error message if a user is trying access Antioch/Chipps
	// quick results and the selected scenario was not run
	// with Antioch/Chipps
	private boolean clsAntiochChipps(String dvFilename) {
		String clsFileName = dvFilename.substring(0, dvFilename.length() - 7) + ".cls";
		File clsF = new File(clsFileName);
		String AN_CHstate = "";
		try {
			Scanner scanner;
			scanner = new Scanner(new FileInputStream(clsF.getAbsolutePath()));
			while (scanner.hasNextLine() && AN_CHstate.equals("")) {
				String text = scanner.nextLine();
				if (text.startsWith("CkbReg_AN|")) {

					String[] texts = text.split("[|]");
					AN_CHstate = texts[1];
				}
			}
			scanner.close();

		} catch (IOException e) {
			LOG.info(clsF.getName() + " not openable - Antioch/Chipps assumed off");
		}

		return (AN_CHstate.equals("true"));
	}

	// Logic to display error message if a user is trying access LVE quick
	// results and the selected scenario was not run with LVE RH
	// 10/2/2014
	/**
	 * Determines if the cls (CalLite scenario) file associated with a given
	 * DV.DSS file shows Los Vaqueros Enlargement on
	 *
	 * @param dvFilename
	 * @return true if .cls shows Antioch/Chipps on, false otherwise
	 */
	private boolean clsLVE(String dvFilename) {
		String clsFileName = dvFilename.substring(0, dvFilename.length() - 7) + ".cls";
		File clsF = new File(clsFileName);
		String LVE_State = "";
		try {
			Scanner scanner;
			scanner = new Scanner(new FileInputStream(clsF.getAbsolutePath()));
			while (scanner.hasNextLine() && LVE_State.equals("")) {
				String text = scanner.nextLine();
				if (text.startsWith("fac_ckb3|")) {

					String[] texts = text.split("[|]");
					LVE_State = texts[1];
				}
			}
			scanner.close();

		} catch (IOException e) {
			LOG.info(clsF.getName() + " not openable - LVE assumed off");
		}

		return (LVE_State.equals("true"));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#hasPower(java.lang.
	 * String)
	 */
	@Override
	public boolean hasPower(String dssFilename) {
		try {
			HecDss hD = HecDss.open(dssFilename);
			@SuppressWarnings("unchecked")

			Vector<String> aList = hD.getPathnameList();
			for (String path : aList) {
				String[] parts = path.split("/");
				if (parts[1].startsWith("HYDROPOWER"))
					return true;
			}
		} catch (Exception e) {

			LOG.debug(e.getMessage());

		}
		return false;
	}

	/**
	 * Reads a specified dataset from a specified HEC DSS file.
	 *
	 * @param dssFilename
	 *            name of HEC DSS file from which to read results
	 * @param dssName
	 *            name(s) of dataset(s) to read from HEC DSS file. Multiple
	 *            dataset names can be specified - separated by "+"; the dataset
	 *            results will be read and summed. if the first data set has the
	 *            suffix (-1), the results read will be shifted one month
	 *            earlier.
	 * @return HEC TimeSeriesContainer with times, values, number of values, and
	 *         file name.
	 */
	protected TimeSeriesContainer getOneSeries(String dssFilename, String dssName) {

		HecDss hD;
		TimeSeriesContainer result = null;

		try {

			hD = HecDss.open(dssFilename);

			// Determine A-part and F-part directly from file - 10/4/2011 -
			// assumes constant throughout

			@SuppressWarnings("unchecked")
			// TODO: is it better to write code that does not require
			// suppresswarnings?
			Vector<String> aList = hD.getPathnameList();
			Iterator<String> it = aList.iterator();
			String aPath = it.next();
			String[] temp = aPath.split("/");
			String hecAPart = temp[1];
			String hecFPart = temp[6] + "/";

			String delims = "[+]";
			String[] dssNames1 = dssName.split(delims);

			// Check for time shift (-1 at end of name)

			boolean doTimeShift = false;
			if (dssNames1[0].endsWith("(-1)")) {
				doTimeShift = true;
				dssNames1[0] = dssNames1[0].substring(0, dssNames1[0].length() - 4);
			}

			// Assign F-Part for each DSS - use default if not specified,
			// otherwise last part in supplied name.

			String[] dssNames = new String[dssNames1.length];
			String[] hecFParts = new String[dssNames1.length];
			for (int i = 0; i < dssNames1.length; i++) {
				String[] nameParts = dssNames1[i].split("/");
				if (nameParts.length < 2)
					dssNames[i] = "MISSING_B/OR_CPART";
				else {
					dssNames[i] = nameParts[0] + "/" + nameParts[1];
					if (nameParts.length == 2)
						hecFParts[i] = hecFPart;
					else {
						// TODO: Use nameParts UNLESS it's "LOOKUP", in which
						// case we should look up the value by matching B and C
						// parts

						hecFParts[i] = nameParts[nameParts.length - 1] + "/";
						if (hecFParts[i].equals("LOOKUP/")) {
							for (String path : aList) {
								String[] parts = path.split("/");
								if (dssNames[i].equals(parts[2] + "/" + parts[3]))
									hecFParts[i] = parts[6] + "/";

							}
						}
					}
				}
			}

			// TODO: Note hard-coded D- and E-PART
			result = (TimeSeriesContainer) hD
					.get("/" + hecAPart + "/" + dssNames[0] + "/01JAN1930/1MON/" + hecFParts[0], true);
			System.out.println("/" + hecAPart + "/" + dssNames[0] + "/01JAN1930/1MON/" + hecFParts[0]);
			if ((result == null) || (result.numberValues < 1)) {

				String message;
				result = null;

				if (!clsIsDynamicSJR(dssFilename) && ((dssNames[0].equals("S_MELON/STORAGE"))
						|| (dssNames[0].equals("S_PEDRO/STORAGE")) || (dssNames[0].equals("S_MCLRE/STORAGE"))
						|| (dssNames[0].equals("S_MLRTN/STORAGE")) || (dssNames[0].equals("C_STANRIPN/FLOW-CHANNEL"))
						|| (dssNames[0].equals("C_TUOL/FLOW-CHANNEL")) || (dssNames[0].equals("C_MERCED2/FLOW-CHANNEL"))
						|| (dssNames[0].equals("C_SJRMS/FLOW-CHANNEL"))
						|| (dssNames[0].equals("D_STANRIPN/FLOW-DELIVERY"))
						|| (dssNames[0].equals("D_STANGDWN/FLOW-DELIVERY"))
						|| (dssNames[0].equals("D_TUOL/FLOW-DELIVERY"))
						|| (dssNames[0].equals("D_TUOL1B/FLOW-DELIVERY"))
						|| (dssNames[0].equals("D_TUOL2/FLOW-DELIVERY"))
						|| (dssNames[0].equals("D_MERCED1/FLOW-DELIVERY"))
						|| (dssNames[0].equals("D_MERCED2/FLOW-DELIVERY"))
						|| (dssNames[0].equals("D_MDRCNL/FLOW-DELIVERY"))
						|| (dssNames[0].equals("D_FKCNL/FLOW-DELIVERY")))) {

					message = " Could not find " + dssNames[0] + " in " + dssFilename
							+ ".\n The selected scenario was not run using dynamic SJR simulation;";

				}

				else if (!clsAntiochChipps(dssFilename)
						&& ((dssNames[0].equals("AN_EC_STD/SALINITY")) || (dssNames[0].equals("CH_EC_STD/SALINITY")))) {

					message = " Could not find " + dssNames[0] + " in " + dssFilename
							+ ".\n The selected scenario was not run with D-1485 Fish and Wildlife (at Antioch and Chipps) regulations.";
				}

				else if (!clsLVE(dssFilename) && ((dssNames[0].equals("S422/STORAGE"))
						|| (dssNames[0].equals("WQ408_OR_/SALINITY")) || (dssNames[0].equals("WQ408_VC_/SALINITY"))
						|| (dssNames[0].equals("WQ408_RS_/SALINITY"))
						|| (dssNames[0].equals("C422_FILL_CC/FLOW-CHANNEL"))
						|| (dssNames[0].equals("D420/FLOW-DELIVERY")) || (dssNames[0].equals("D408_OR/FLOW-DELIVERY"))
						|| (dssNames[0].equals("D408_VC/FLOW-DELIVERY"))
						|| (dssNames[0].equals("D408_RS/FLOW-DELIVERY")) || (dssNames[0].equals("WQ420/SALINITY")))) {

					message = "Could not find " + dssNames[0] + " in " + dssFilename
							+ ".\n The selected scenario was not run with Los Vaqueros Enlargement.";
				}

				else {
					message = "Could not find " + dssNames[0] + " in " + dssFilename;
				}
				if (stopOnMissing)
					dialogSvc.getOK("Could not find " + dssNames[0] + " in " + dssFilename, JOptionPane.ERROR_MESSAGE);
				missingDSSRecords.add(message);
			} else {

				// If no error, add results from other datasets in dssNames
				// array.

				for (int i = 1; i < dssNames.length; i++) {
					// TODO: Note hard-coded D- and E-PART
					TimeSeriesContainer result2 = (TimeSeriesContainer) hD
							.get("/" + hecAPart + "/" + dssNames[i] + "/01JAN2020/1MON/" + hecFParts[i], true);
					if (result2 == null || result2.numberValues < 1) {
						result2 = null;
						String message = "Could not find " + dssNames[0] + " in " + dssFilename;
						if (stopOnMissing)
							JOptionPane.showMessageDialog(null, message, "Error", JOptionPane.ERROR_MESSAGE);
						else
							missingDSSRecords.add(message);
					} else {
						for (int j = 0; j < result2.numberValues; j++)
							result.values[j] = result.values[j] + result2.values[j];
					}
				}
			}

			// Trim to date range
			if (result != null) {
				int first = 0; // Find starting index
				for (int i = 0; (i < result.numberValues) && (result.times[i] < startTime); i++)
					first = i + 1;

				int last = result.numberValues - 1; // find ending index
				for (int i = result.numberValues - 1; (i >= 0) && (result.times[i] >= endTime); i--)
					last = i;

				if (first != 0) // Shift results in array to start
					for (int i = 0; i <= (last - first); i++) { // TODO: Think
																// through
																// change to
																// <= done 11/9
						result.times[i] = result.times[i + first];
						result.values[i] = result.values[i + first];
					}

				result.numberValues = last - first + 1; // Adjust count of
														// results

				// Do time shift where indicated (when a dataset has suffix
				// "(-1)"

				if (doTimeShift) {
					for (int i = result.numberValues; i < result.numberValues - 1; i++)
						result.times[i] = result.times[i + 1];
					result.numberValues = result.numberValues - 1;
				}
			}

		} catch (

		Exception e) {

			LOG.debug(e.getMessage());
			LOG.error(e.getMessage());
			if (e.getMessage().contains("Unable to recognize record")) {
				this.missingDSSRecords.add("Could not find record - HEC message was '" + e.getMessage() + "'");
			} else {
				String messageText = "Unable to get time series." + e.getMessage();

				errorHandlingSvc.businessErrorHandler(messageText, (JFrame) swingEngine.find(Constant.MAIN_FRAME_NAME),
						e);
			}
		}

		// Store name portion of DSS file in TimeSeriesContainer

		String shortFileName = new File(dssFilename).getName();
		if (result != null)
			result.fileName = shortFileName;

		return result;
	}

	protected String checkReadiness() {
		String result = null;
		if (startTime == -1)
			result = "Date range is not set in DSSGrabber.";
		else if (baseName == null)
			result = "Base scenario is not set in DSSGrabber.";
		else if (primaryDSSName == null)
			result = "Base scenario is not set in DSSGrabber.";
		LOG.debug(result);
		return result;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#getPrimarySeries(
	 * java.lang.String)
	 */
	@Override
	public TimeSeriesContainer[] getPrimarySeries(String locationName) {

		TimeSeriesContainer[] results = null;

		try {
			if (checkReadiness() != null)
				throw new NullPointerException(checkReadiness());

			else {

				if (locationName.contains(Constant.SCHEMATIC_PREFIX) && primaryDSSName.contains(",")) {

					// Special handling for DEMO of schematic view - treat
					// multiple
					// series as multiple scenarios
					// TODO: Longer-term approach is probably to add a rank to
					// arrays storing all series

					String[] dssNames = primaryDSSName.split(",");
					scenarios = dssNames.length;
					results = new TimeSeriesContainer[scenarios];
					for (int i = 0; i < scenarios; i++)
						results[i] = getOneSeries(baseName, dssNames[i]);

					originalUnits = results[0].units;

				} else {

					// Store number of scenarios

					scenarios = lstScenarios.getModel().getSize();
					results = new TimeSeriesContainer[scenarios];

					// Base first

					results[0] = getOneSeries(baseName, primaryDSSName);
					originalUnits = results[0].units;

					// Then scenarios

					int j = 0;
					for (int i = 0; i < scenarios; i++) {
						String scenarioName;
						if (baseName.toUpperCase().contains("_SV.DSS")) {
							// For SVars, use WRIMS GUI Project object to
							// determine
							// input files
							switch (i) {
							case 0:
								scenarioName = project.getSVFile();
								break;
							case 1:
								scenarioName = project.getSV2File();
								break;
							case 2:
								scenarioName = project.getSV3File();
								break;
							case 3:
								scenarioName = project.getSV4File();
								break;
							default:
								scenarioName = "";
								break;
							}
						} else
							scenarioName = ((RBListItemBO) lstScenarios.getModel().getElementAt(i)).toString();
						if (!baseName.equals(scenarioName)) {
							j = j + 1;
							results[j] = getOneSeries(scenarioName, primaryDSSName);
						}
					}
				}
			}
		} catch (Exception e) {
			LOG.error(e.getMessage());
			String messageText = "Unable to get time series.";
			errorHandlingSvc.businessErrorHandler(messageText, (JFrame) swingEngine.find(Constant.MAIN_FRAME_NAME), e);
		}

		return results;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#getSecondarySeries()
	 */
	@Override
	public TimeSeriesContainer[] getSecondarySeries() {

		if (secondaryDSSName.equals("") || secondaryDSSName.equals("null")) {
			return null;
		} else {

			scenarios = lstScenarios.getModel().getSize();
			TimeSeriesContainer[] results = new TimeSeriesContainer[scenarios];

			// Base first

			results[0] = getOneSeries(baseName, secondaryDSSName);

			// Then scenarios

			int j = 0;
			for (int i = 0; i < scenarios; i++) {
				// String scenarioName = (String)
				// lstScenarios.getModel().getElementAt(i);
				String scenarioName = ((RBListItemBO) lstScenarios.getModel().getElementAt(i)).toString();

				if (!baseName.equals(scenarioName)) {
					j = j + 1;
					results[j] = getOneSeries(scenarioName, secondaryDSSName);
				}
			}
			return results;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#getDifferenceSeries(
	 * hec.io.TimeSeriesContainer[])
	 */
	@Override
	public TimeSeriesContainer[] getDifferenceSeries(TimeSeriesContainer[] timeSeriesResults) {

		TimeSeriesContainer[] results = new TimeSeriesContainer[scenarios - 1];
		for (int i = 0; i < scenarios - 1; i++) {

			results[i] = (TimeSeriesContainer) timeSeriesResults[i + 1].clone();
			for (int j = 0; j < results[i].numberValues; j++)
				results[i].values[j] = results[i].values[j] - timeSeriesResults[0].values[j];
		}
		return results;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#calcTAFforCFS(hec.io
	 * .TimeSeriesContainer[], hec.io.TimeSeriesContainer[])
	 */
	@Override
	public void calcTAFforCFS(TimeSeriesContainer[] primaryResults, TimeSeriesContainer[] secondaryResults) {

		try {
			// Allocate and zero out

			int datasets = primaryResults.length;
			if (secondaryResults != null)
				datasets = datasets + secondaryResults.length;

			annualTAFs = new double[datasets][endWY - startWY + 2];

			for (int i = 0; i < datasets; i++)
				for (int j = 0; j < endWY - startWY + 1; j++)
					annualTAFs[i][j] = 0.0;

			// Calculate

			if (originalUnits.equals("CFS")) {

				HecTime ht = new HecTime();
				Calendar calendar = Calendar.getInstance();

				// Primary series

				for (int i = 0; i < primaryResults.length; i++) {
					for (int j = 0; j < primaryResults[i].numberValues; j++) {

						ht.set(primaryResults[i].times[j]);
						calendar.set(ht.year(), ht.month() - 1, 1);
						double monthlyTAF = primaryResults[i].values[j]
								* calendar.getActualMaximum(Calendar.DAY_OF_MONTH) * CFS_2_TAF_DAY;
						int wy = ((ht.month() < 10) ? ht.year() : ht.year() + 1) - startWY;
						if (wy >= 0)
							annualTAFs[i][wy] += monthlyTAF;
						if (!isCFS)
							primaryResults[i].values[j] = monthlyTAF;
					}
					if (!isCFS)
						primaryResults[i].units = "TAF per year";
				}

				// Calculate differences if applicable (primary series only)

				if (primaryResults.length > 1) {
					annualTAFsDiff = new double[primaryResults.length - 1][endWY - startWY + 2];
					for (int i = 0; i < primaryResults.length - 1; i++)
						for (int j = 0; j < endWY - startWY + 1; j++)
							annualTAFsDiff[i][j] = annualTAFs[i + 1][j] - annualTAFs[0][j];
				}

				if (secondaryResults != null) {

					// Secondary series

					for (int i = 0; i < secondaryResults.length; i++) {
						for (int j = 0; j < secondaryResults[i].numberValues; j++) {

							ht.set(secondaryResults[i].times[j]);
							calendar.set(ht.year(), ht.month() - 1, 1);
							double monthlyTAF = secondaryResults[i].values[j]
									* calendar.getActualMaximum(Calendar.DAY_OF_MONTH) * CFS_2_TAF_DAY;
							int wy = ((ht.month() < 10) ? ht.year() : ht.year() + 1) - startWY;
							annualTAFs[i + primaryResults.length][wy] += monthlyTAF;
							if (!isCFS)
								secondaryResults[i].values[j] = monthlyTAF;

						}
						if (!isCFS)
							secondaryResults[i].units = "TAF per year";
					}
				}
			}
		} catch (Exception e) {
			LOG.error(e.getMessage());
			String messageText = "Unable to calculate TAF.";
			errorHandlingSvc.businessErrorHandler(messageText, (JFrame) swingEngine.find(Constant.MAIN_FRAME_NAME), e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#getAnnualTAF(int,
	 * int)
	 */
	@Override
	public double getAnnualTAF(int i, int wy) {

		return wy < startWY ? -1 : annualTAFs[i][wy - startWY];
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#getAnnualTAFDiff(
	 * int, int)
	 */
	@Override
	public double getAnnualTAFDiff(int i, int wy) {

		return wy < startWY ? -1 : annualTAFsDiff[i][wy - startWY];
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#getAnnualCFS(int,
	 * int)
	 */
	@Override
	public double getAnnualCFS(int i, int wy) {

		return wy < startWY ? -1 : annualCFSs[i][wy - startWY];
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#getAnnualCFSDiff(
	 * int, int)
	 */
	@Override
	public double getAnnualCFSDiff(int i, int wy) {

		return wy < startWY ? -1 : annualCFSsDiff[i][wy - startWY];
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#calcCFSforTAF(hec.io
	 * .TimeSeriesContainer[], hec.io.TimeSeriesContainer[])
	 */
	@Override
	public void calcCFSforTAF(TimeSeriesContainer[] primaryResults, TimeSeriesContainer[] secondaryResults) {

		try {
			// Allocate and zero out

			int datasets = primaryResults.length;
			if (secondaryResults != null)
				datasets = datasets + secondaryResults.length;

			annualCFSs = new double[datasets][endWY - startWY + 2];

			for (int i = 0; i < datasets; i++)
				for (int j = 0; j < endWY - startWY + 1; j++)
					annualCFSs[i][j] = 0.0;

			// Calculate

			if (originalUnits.equals("TAF")) {

				HecTime ht = new HecTime();
				Calendar calendar = Calendar.getInstance();

				// Primary series

				for (int i = 0; i < primaryResults.length; i++) {
					for (int j = 0; j < primaryResults[i].numberValues; j++) {

						ht.set(primaryResults[i].times[j]);
						calendar.set(ht.year(), ht.month() - 1, 1);
						double monthlyCFS = primaryResults[i].values[j]
								* calendar.getActualMaximum(Calendar.DAY_OF_MONTH) * TAF_DAY_2_CFS;
						int wy = ((ht.month() < 10) ? ht.year() : ht.year() + 1) - startWY;
						if (wy >= 0)
							annualCFSs[i][wy] += monthlyCFS;
						if (!isCFS)
							primaryResults[i].values[j] = monthlyCFS;
					}
					if (isCFS)
						primaryResults[i].units = "cfs";
				}

				// Calculate differences if applicable (primary series only)

				if (primaryResults.length > 1) {
					annualCFSsDiff = new double[primaryResults.length - 1][endWY - startWY + 2];
					for (int i = 0; i < primaryResults.length - 1; i++)
						for (int j = 0; j < endWY - startWY + 1; j++)
							annualCFSsDiff[i][j] = annualCFSs[i + 1][j] - annualCFSs[0][j];
				}

				if (secondaryResults != null) {

					// Secondary series

					for (int i = 0; i < secondaryResults.length; i++) {
						for (int j = 0; j < secondaryResults[i].numberValues; j++) {

							ht.set(secondaryResults[i].times[j]);
							calendar.set(ht.year(), ht.month() - 1, 1);
							double monthlyCFS = secondaryResults[i].values[j]
									* calendar.getActualMaximum(Calendar.DAY_OF_MONTH) * TAF_DAY_2_CFS;
							int wy = ((ht.month() < 10) ? ht.year() : ht.year() + 1) - startWY;
							annualCFSs[i + primaryResults.length][wy] += monthlyCFS;
							if (isCFS)
								secondaryResults[i].values[j] = monthlyCFS;

						}
						if (isCFS)
							secondaryResults[i].units = "cfs";
					}
				}
			}
		} catch (Exception e) {
			LOG.error(e.getMessage());
			String messageText = "Unable to calculate CFS.";
			errorHandlingSvc.businessErrorHandler(messageText, (JFrame) swingEngine.find(Constant.MAIN_FRAME_NAME), e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#getExceedanceSeries(
	 * hec.io.TimeSeriesContainer[])
	 */
	@Override
	public TimeSeriesContainer[][] getExceedanceSeries(TimeSeriesContainer[] timeSeriesResults) {

		TimeSeriesContainer[][] results;
		try {
			if (timeSeriesResults == null || timeSeriesResults[0] == null || timeSeriesResults[0].times == null)
				results = null;
			else {
				results = new TimeSeriesContainer[14][scenarios];

				for (int month = 0; month < 14; month++) {

					HecTime ht = new HecTime();
					for (int i = 0; i < scenarios; i++) {

						if (month == 13) {
							results[month][i] = (TimeSeriesContainer) timeSeriesResults[i].clone();
						} else {

							int n;
							int times2[];
							double values2[];

							results[month][i] = new TimeSeriesContainer();

							if (month == 12) {

								// Annual totals - grab from annualTAFs
								n = annualTAFs[i].length;
								times2 = new int[n];
								values2 = new double[n];
								for (int j = 0; j < n; j++) {
									ht.setYearMonthDay(j + startWY, 11, 1, 0);
									times2[j] = ht.value();
									values2[j] = annualTAFs[i][j];
								}

							} else {

								int[] times = timeSeriesResults[i].times;
								double[] values = timeSeriesResults[i].values;

								n = 0;
								for (int j = 0; j < times.length; j++) {
									ht.set(times[j]);
									if (ht.month() == month + 1)
										n = n + 1;
								}

								times2 = new int[n];
								values2 = new double[n];
								n = 0;
								for (int j = 0; j < times.length; j++) {
									ht.set(times[j]);
									if (ht.month() == month + 1) {
										times2[n] = times[j];
										values2[n] = values[j];
										n = n + 1;
									}
								}
							}
							results[month][i].times = times2;
							results[month][i].values = values2;
							results[month][i].numberValues = n;
							results[month][i].units = timeSeriesResults[i].units;
							results[month][i].fullName = timeSeriesResults[i].fullName;
							results[month][i].fileName = timeSeriesResults[i].fileName;
						}
						if (results[month][i].values != null) {
							double[] sortArray = results[month][i].values;
							Arrays.sort(sortArray);
							results[month][i].values = sortArray;
						}
					}
				}
			}
			return results;
		} catch (Exception e) {
			LOG.error(e.getMessage());
			String messageText = "Unable to get time-series.";
			errorHandlingSvc.businessErrorHandler(messageText, (JFrame) swingEngine.find(Constant.MAIN_FRAME_NAME), e);
		}
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#getExceedanceSeriesD
	 * (hec.io.TimeSeriesContainer[])
	 */
	@Override
	public TimeSeriesContainer[][] getExceedanceSeriesD(TimeSeriesContainer[] timeSeriesResults) {

		/*
		 * Copy of getExceedanceSeries to handle "exceedance of differences"
		 *
		 * Calculates difference of annual TAFs to get proper results for [12];
		 * should be recombined with getExceedanceSeries
		 */

		try {
			TimeSeriesContainer[][] results;
			if (timeSeriesResults == null)
				results = null;
			else {
				results = new TimeSeriesContainer[14][scenarios - 1];

				for (int month = 0; month < 14; month++) {

					HecTime ht = new HecTime();
					for (int i = 0; i < scenarios - 1; i++) {

						if (month == 13) {

							results[month][i] = (TimeSeriesContainer) timeSeriesResults[i + 1].clone();
							for (int j = 0; j < results[month][i].numberValues; j++)
								results[month][i].values[j] -= timeSeriesResults[0].values[j];

						} else {

							int n;
							int times2[];
							double values2[];

							results[month][i] = new TimeSeriesContainer();

							if (month == 12) {

								// Annual totals - grab from annualTAFs
								n = annualTAFs[i + 1].length;
								times2 = new int[n];
								values2 = new double[n];
								for (int j = 0; j < n; j++) {
									ht.setYearMonthDay(j + startWY, 11, 1, 0);
									times2[j] = ht.value();
									values2[j] = annualTAFs[i + 1][j] - annualTAFs[0][j];
								}

							} else {

								int[] times = timeSeriesResults[i + 1].times;
								double[] values = timeSeriesResults[i + 1].values;
								n = 0;
								for (int j = 0; j < times.length; j++) {
									ht.set(times[j]);
									if (ht.month() == month + 1)
										n = n + 1;
								}
								times2 = new int[n];
								values2 = new double[n];
								int nmax = n; // Added to trap Schematic View
												// case
												// where required flow has extra
												// values
								n = 0;
								for (int j = 0; j < times.length; j++) {
									ht.set(times[j]);
									if ((ht.month() == month + 1) && (n < nmax)
											&& (j < timeSeriesResults[0].values.length)) {
										times2[n] = times[j];
										values2[n] = values[j] - timeSeriesResults[0].values[j];
										n = n + 1;
									}
								}
							}
							results[month][i].times = times2;
							results[month][i].values = values2;
							results[month][i].numberValues = n;
							results[month][i].units = timeSeriesResults[i + 1].units;
							results[month][i].fullName = timeSeriesResults[i + 1].fullName;
							results[month][i].fileName = timeSeriesResults[i + 1].fileName;
						}
						if (results[month][i].values != null) {
							double[] sortArray = results[month][i].values;
							Arrays.sort(sortArray);
							results[month][i].values = sortArray;
						}
					}
				}
			}
			return results;
		} catch (Exception e) {
			LOG.error(e.getMessage());
			String messageText = "Unable to get time-series.";
			errorHandlingSvc.businessErrorHandler(messageText, (JFrame) swingEngine.find(Constant.MAIN_FRAME_NAME), e);
		}
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#getOriginalUnits()
	 */
	@Override
	public String getOriginalUnits() {
		return originalUnits;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#setOriginalUnits(
	 * java.lang.String)
	 */
	@Override
	public void setOriginalUnits(String originalUnits) {
		this.originalUnits = originalUnits;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#getPrimaryDSSName()
	 */
	@Override
	public String getPrimaryDSSName() {
		return primaryDSSName;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * gov.ca.water.calgui.bus_service.impl.IDSSGrabber1Svc#setPrimaryDSSName(
	 * java.lang.String)
	 */
	@Override
	public void setPrimaryDSSName(String primaryDSSName) {
		this.primaryDSSName = primaryDSSName;
	}

}
