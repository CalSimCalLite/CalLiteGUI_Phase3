package gov.ca.water.calgui.presentation;

import java.awt.HeadlessException;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;

import org.apache.log4j.Logger;
import org.swixml.SwingEngine;

import gov.ca.water.calgui.bus_delegate.IApplyDynamicConDele;
import gov.ca.water.calgui.bus_delegate.impl.ApplyDynamicConDeleImp;
import gov.ca.water.calgui.bus_service.impl.XMLParsingSvcImpl;
import gov.ca.water.calgui.constant.Constant;
import gov.ca.water.calgui.tech_service.IDialogSvc;
import gov.ca.water.calgui.tech_service.IErrorHandlingSvc;
import gov.ca.water.calgui.tech_service.impl.DialogSvcImpl;
import gov.ca.water.calgui.tech_service.impl.ErrorHandlingSvcImpl;

/**
 * This class is for Listening all the mouse events which are generated by the
 * application.
 *
 * @author Mohan
 */
public class GlobalMouseListener implements MouseListener {

	private static final Logger LOG = Logger.getLogger(GlobalMouseListener.class.getName());
	private IApplyDynamicConDele applyDynamicConDele = new ApplyDynamicConDeleImp();
	private SwingEngine swingEngine = XMLParsingSvcImpl.getXMLParsingSvcImplInstance().getSwingEngine();
	private IErrorHandlingSvc errorHandlingSvc = new ErrorHandlingSvcImpl();
	private IDialogSvc dialogSvc = DialogSvcImpl.getDialogSvcInstance();

	@Override
	public void mouseClicked(MouseEvent me) {
		try {
			LOG.debug("mouseClicked");
			JComponent component = (JComponent) me.getComponent();
			String cName = component.getName();

			if (SwingUtilities.isRightMouseButton(me)) {

				// Right-click on a regulations checkbox updates right-hand
				// panel

				// if (((JCheckBox) component).isSelected()) {
				applyDynamicConDele.applyDynamicControl(cName, ((JCheckBox) component).isSelected(),
						((JCheckBox) component).isEnabled(), false);
				// the false value don't mean any thing because we implement
				// right
				// click on only check box.
				if (cName.equals("ckbReg_TRNTY") || cName.equals("ckbReg_PUMP")) {

					// Special handling for Trinity and Pumping regulations

					// These two regulations cannot be turned off, so their
					// checkboxes are not enabled under user-defined. This code
					// forces the display of the D1485/D-1641 selector in
					// reg_panTab

					((JButton) swingEngine.find("btnRegCopy")).setEnabled(false);
					((JButton) swingEngine.find("btnRegPaste")).setEnabled(false);
					((JRadioButton) swingEngine.find("btnRegD1641")).setEnabled(true);
					((JRadioButton) swingEngine.find("btnRegD1485")).setEnabled(true);

					((JPanel) this.swingEngine.find("reg_panTab")).setVisible(true);
					((JPanel) this.swingEngine.find("reg_panTabPlaceholder")).setVisible(false);
				}
				LOG.debug(cName);
			} else {
				// Otherwise, we're looking for a double-click on a "ckbp"
				// checkbox from quick results.
				int button = me.getButton();
				Integer iClickCount = me.getClickCount();
				if (button != MouseEvent.NOBUTTON && button != MouseEvent.BUTTON1) {
					// Nothing for right mousepress
				} else {
					// Double Click
					if (iClickCount == 2) {
						if (cName.startsWith("ckbp")) {
							JCheckBox chk = (JCheckBox) component;
							JList lstScenarios = (JList) swingEngine.find("SelectedList");
							if (lstScenarios.getModel().getSize() == 0) {
								// JOptionPane.showMessageDialog(swingEngine.find(Constant.MAIN_FRAME_NAME),
								// "No scenarios loaded", "Error",
								// JOptionPane.ERROR_MESSAGE);
								// ImageIcon icon = new
								// ImageIcon(getClass().getResource("/images/CalLiteIcon.png"));
								// Object[] options = { "OK" };
								// JOptionPane optionPane = new JOptionPane("No
								// scenarios loaded",
								// JOptionPane.ERROR_MESSAGE,
								// JOptionPane.OK_OPTION, null, options,
								// options[0]);
								// JDialog dialog =
								// optionPane.createDialog(swingEngine.find(Constant.MAIN_FRAME_NAME),"CalLite");
								// dialog.setIconImage(icon.getImage());
								// dialog.setResizable(false);
								// dialog.setVisible(true);
								dialogSvc.getOK("Error - No scenarios loaded", JOptionPane.ERROR_MESSAGE);
							} else {
								DisplayFrame.showDisplayFrames(DisplayFrame.quickState() + ";Locs-" + chk.getText()
										+ ";Index-" + chk.getName(), lstScenarios);
							}

						}
					}
					// Placeholder for future handling of double-clicks
				}
			}
		} catch (HeadlessException e) {
			LOG.error(e.getMessage());
			String messageText = "Unable to initialize mouse listeners.";
			errorHandlingSvc.businessErrorHandler(messageText, (JFrame) swingEngine.find(Constant.MAIN_FRAME_NAME), e);
		}
	}

	@Override
	public void mouseReleased(MouseEvent arg0) {
	}

	@Override
	public void mouseEntered(MouseEvent arg0) {
	}

	@Override
	public void mouseExited(MouseEvent arg0) {
	}

	@Override
	public void mousePressed(MouseEvent arg0) {
	}
}