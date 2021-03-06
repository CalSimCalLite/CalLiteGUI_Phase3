package gov.ca.water.calgui.presentation;

import java.awt.Component;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.apache.log4j.Logger;
import org.swixml.SwingEngine;

import gov.ca.water.calgui.bo.JLinkedSlider;
import gov.ca.water.calgui.bus_service.impl.ModelRunSvcImpl;
import gov.ca.water.calgui.bus_service.impl.XMLParsingSvcImpl;
import gov.ca.water.calgui.constant.Constant;
import gov.ca.water.calgui.tech_service.IErrorHandlingSvc;
import gov.ca.water.calgui.tech_service.impl.ErrorHandlingSvcImpl;

/**
 * This class is for Listening all the change events which are generated by the
 * application.(We only use this for "Regulations" tab)
 *
 * @author Mohan
 */
public class GlobalChangeListener implements ChangeListener {

	private static final Logger LOG = Logger.getLogger(GlobalChangeListener.class.getName());
	private SwingEngine swingEngine = XMLParsingSvcImpl.getXMLParsingSvcImplInstance().getSwingEngine();
	private IErrorHandlingSvc errorHandlingSvc = new ErrorHandlingSvcImpl();

	@Override
	public void stateChanged(ChangeEvent changeEvent) {
		try {
			String lcName = ((Component) changeEvent.getSource()).getName().toLowerCase();
			LOG.debug(lcName);
			if (lcName.equals("reg_tabbedpane")) {
				((JComponent) swingEngine.find("scrRegValues")).setVisible(false);
				((JPanel) swingEngine.find("reg_panTab")).setBorder(BorderFactory.createTitledBorder("Values"));
				boolean showTablePanel = ((JRadioButton) swingEngine.find("rdbRegQS_UD")).isSelected()
						&& (((JTabbedPane) changeEvent.getSource()).getSelectedIndex() != 2); // DO
																								// NOT
																								// SHOW
																								// table
																								// value
																								// panel
																								// for
																								// SJR
																								// tab

				showTablePanel = showTablePanel || (lcName.equals("ckbReg_TRNTY") || lcName.equals("ckbReg_PUMP"));
				// Hide/show reg_panTab as needed and selected - will be updated by
				// focus control

				// Force display of panel for Trinity, Pumping
				// showTablePanel = showTablePanel || (lcName.equals("ckbReg_TRNTY")
				// || lcName.equals("ckbReg_PUMP"));

				((JPanel) this.swingEngine.find("reg_panTab")).setVisible(showTablePanel);
				((JPanel) this.swingEngine.find("reg_panTabPlaceholder")).setVisible(!showTablePanel);

			} else if (lcName.equals("run_sldthreads")) {
				ModelRunSvcImpl.simultaneousRuns = ((JSlider) changeEvent.getSource()).getValue();
				((JLabel) swingEngine.find("run_lblThreads")).setText(" " + ModelRunSvcImpl.simultaneousRuns + " run"
						+ ((ModelRunSvcImpl.simultaneousRuns > 1) ? "s" : ""));

			} else if (changeEvent.getSource() instanceof JLinkedSlider) {

				// Handle LinkedSlider ...

				String sldrName = ((Component) changeEvent.getSource()).getName();
				JLinkedSlider sldr = (JLinkedSlider) swingEngine.find(sldrName);
				if (sldr.getRTextBoxID() != "") {
					JTextField txtfR = (JTextField) swingEngine.find(sldr.getRTextBoxID());
					txtfR.setText(Integer.toString(sldr.getValue()));
				}
				if (sldr.getLTextBoxID() != "") {
					JTextField txtfL = (JTextField) swingEngine.find(sldr.getLTextBoxID());
					int leftVal = sldr.getMaximum() - sldr.getValue();
					txtfL.setText(Integer.toString(leftVal));
				}
			}
		} catch (Exception e) {
			LOG.error(e.getMessage());
			String messageText = "Unable to initialize change listener.";
			errorHandlingSvc.businessErrorHandler(messageText,(JFrame) swingEngine.find(Constant.MAIN_FRAME_NAME), e);
		}
	}
}