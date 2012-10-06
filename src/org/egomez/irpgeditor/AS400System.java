package org.egomez.irpgeditor;

/*
 * Copyright:    Copyright (c) 2004
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software; see the file COPYING.  If not, write to
 * the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 * Boston, MA 02111-1307 USA
 */

import java.sql.*;
import java.util.*;
import javax.swing.*;
import com.ibm.as400.data.*;
import com.ibm.as400.access.*;

import org.egomez.irpgeditor.env.*;
import org.egomez.irpgeditor.event.*;
import org.egomez.irpgeditor.icons.*;
import org.egomez.irpgeditor.tree.*;

/**
 * A connection an as400. Contains the information for how to connect to a
 * specific as400 system. Has some utility functions for getting source
 * libraries, source files, list of members and so on.
 * 
 * @author Derek Van Kooten.
 */
public class AS400System extends NodeAbstract {
	AS400JDBCDriver driver = new AS400JDBCDriver();
	ArrayList listListeners = new ArrayList();
	AS400ConnectionPoolImp pool;
	ProgramCallDocument pcml;
	Object pcmlLock = new Object();
	CommandCall commandCall;
	Connection connection;
	AS400 as400;
	String name;
	String address;
	String user;
	String password;
	String errorMessage = "";
	boolean connected = false;
	int alias = 0;
	boolean tempSrcTableCreated = false;
	boolean uploadProcedureExists = false;

	/**
	 * all the connection properties for the system are not known yet.
	 */
	public AS400System() {
	}

	/**
	 * Creates a system with all the connection information. does not connect to
	 * the system, you must call connect or attemptConnect first.
	 * 
	 * @param name
	 *            String The name you use refer to this as400 system.
	 * @param address
	 *            String The i.p. address or url to use to connect.
	 * @param user
	 *            String The user name to use to connect.
	 * @param password
	 *            String The password to use to connect.
	 */
	public AS400System(String name, String address, String user, String password) {
		this.name = name;
		this.address = address;
		this.user = user;
		this.password = password;
	}

	/**
	 * returns the as400 object that this object is connected to.
	 * 
	 * @return AS400
	 */
	public AS400 getAS400() {
		return as400;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		if (this.address != null && this.address.equalsIgnoreCase(address)) {
			return;
		}
		this.address = address;
		attemptConnect();
	}

	public String getUser() {
		return user;
	}

	public void setUser(String user) {
		if (this.user != null && this.user.equalsIgnoreCase(user)) {
			return;
		}
		this.user = user;
		attemptConnect();
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		if (this.password != null && this.password.equalsIgnoreCase(password)) {
			return;
		}
		this.password = password;
		attemptConnect();
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public boolean isConnected() {
		return connected;
	}

	/**
	 * checks to see if all the properties are not null and if not then connect.
	 * if there is an error it records the error in error message.
	 * 
	 * calls disconnect first. then calls the connect method.
	 * 
	 * @return boolean The current connection state.
	 */
	public boolean attemptConnect() {
		disconnect();
		if (address == null) {
			return connected;
		}
		if (user == null) {
			return connected;
		}
		if (password == null) {
			return connected;
		}
		try {
			connect();
		} catch (Exception e) {
			errorMessage = e.getMessage();
			e.printStackTrace();
		}
		return connected;
	}

	/**
	 * Creates an AS400 object, and connects the FILE and DATABASE services. For
	 * more info on AS400 object and the FILE and DATABASE services see the
	 * jtopen project.
	 * 
	 * @throws Exception
	 *             if there is an error connecting to the as400.
	 */
	public void connect() throws Exception {
		disconnect();
		as400 = new AS400(address, user, password);
		pool = new AS400ConnectionPoolImp(address, user, password);
		as400.setGuiAvailable(false);
		as400.connectService(AS400.FILE);
		as400.connectService(AS400.DATABASE);
		connection = driver.connect(as400, getConnectionProperties(), null);
		commandCall = new CommandCall(as400);
		uploadProcExists();
		acreateTempSrcTable();
		acreateUploadProc();
		connected = true;
		fireConnected();
	}

	/**
	 * Returns properties needed for a connection to the as400.
	 * 
	 * @return Properties
	 */
	protected Properties getConnectionProperties() {
		Properties p = new Properties();
		p.setProperty("naming", "system");
		p.setProperty("translate binary", "true");
		return p;
	}

	/**
	 * Returns the connection to the as400. This connection is not a new
	 * connection for every time this method is called. This connection is the
	 * same connection for every time thid method is called.
	 * 
	 * @return Connection
	 */
	public Connection getConnection() {
		return connection;
	}

	public Connection getConnectionPool() throws SQLException {
		return pool.getConnection();
	}

	/**
	 * disconnects from the as400.
	 */
	public void disconnect() {
		connected = false;
		if (as400 != null) {
			as400.disconnectAllServices();
			as400 = null;
			try {
				connection.close();
			} catch (Exception e) {
			}
			connection = null;
			commandCall = null;
			fireDisconnected();
		}
	}

	/**
	 * Adds an object to a list of objects that will be notified of events that
	 * this object produces.
	 * 
	 * @param listener
	 *            ListenerAS400System Will be notified of events.
	 */
	public void addListener(ListenerAS400System listener) {
		listListeners.add(listener);
	}

	/**
	 * Removes an object from the list of objects that will be notified of
	 * events that this object produces.
	 * 
	 * @param listener
	 *            ListenerAS400System Will not be notified of events.
	 */
	public void removeListener(ListenerAS400System listener) {
		listListeners.remove(listener);
	}

	/**
	 * fires an event that the as400 is connected.
	 */
	protected void fireConnected() {
		ArrayList temp;

		temp = (ArrayList) listListeners.clone();
		for (int x = 0; x < temp.size(); x++) {
			((ListenerAS400System) temp.get(x)).connected(this);
		}
	}

	/**
	 * fires an event that the as400 is disconnected.
	 */
	protected void fireDisconnected() {
		ArrayList temp;

		temp = (ArrayList) listListeners.clone();
		for (int x = 0; x < temp.size(); x++) {
			((ListenerAS400System) temp.get(x)).disconnected(this);
		}
	}

	public void createUserSpace(String name, int size) throws Exception {
		if (pcml == null) {
			pcml = new ProgramCallDocument(as400, "api");
		}
		pcml.setValue("quscrtus.name", name);
		pcml.setValue("quscrtus.size", new Integer(size));
		// create the user space only once.
		try {
			callPcml("quscrtus");
		} catch (Exception e) {
		}
	}

	public int getUserSpaceListOffset(String name) throws Exception {
		Object o;

		if (pcml == null) {
			pcml = new ProgramCallDocument(as400, "api");
		}
		pcml.setValue("qusrtvus.userSpace", name);
		pcml.setValue("qusrtvus.startPos", new Integer(125));
		callPcml("qusrtvus");
		o = pcml.getValue("qusrtvus.receiver");
		return ((Integer) o).intValue() + 1;
	}

	public int getUserSpaceNumberOfEntries(String name) throws Exception {
		Object o;

		if (pcml == null) {
			pcml = new ProgramCallDocument(as400, "api");
		}
		pcml.setValue("qusrtvus.userSpace", name);
		pcml.setValue("qusrtvus.startPos", new Integer(133));
		callPcml("qusrtvus");
		o = pcml.getValue("qusrtvus.receiver");
		return ((Integer) o).intValue();
	}

	public String createName(String name, String library) {
		while (name.length() < 10) {
			name = name + " ";
		}
		name = name + library;
		while (name.length() < 20) {
			name = name + " ";
		}
		return name;
	}

	/**
	 * Returns an array list of Member objects that are members of the library
	 * and file specified. The api.pcml file must be in the class path for this
	 * method to work. This method uses IBM's PCML to retrieve the list of
	 * members.
	 * 
	 * @param library
	 *            String The name of the library on the as400 to retrieve the
	 *            members from.
	 * @param file
	 *            String The name of the file on the as400 to retrieve the
	 *            members from.
	 * @throws Exception
	 *             If there is an error getting the members.
	 * @return ArrayList Contains Member objects that reside in the library and
	 *         file specified.
	 */
	public ArrayList listMembers(String library, String file) throws Exception {
		int listOffset = 0, listEntries = 0, size;
		ArrayList members;
		Member member;
		Object o;
		String buffer, userSpaceName;

		if (connected == false) {
			throw new Exception("Invalid State: DISCONNECTED.");
		}
		if (file == null) {
			throw new Exception("File must be specified.");
		}
		buffer = createName(file, library);
		userSpaceName = createName(file, "QTEMP");
		synchronized (pcmlLock) {
			createUserSpace(userSpaceName, 2024 * 2024);

			// retrieve the list of members into the user space on the as400.
			pcml.setValue("quslmbr.userSpace", userSpaceName);
			pcml.setValue("quslmbr.fileName", buffer);
			callPcml("quslmbr");

			listOffset = getUserSpaceListOffset(userSpaceName);
			listEntries = getUserSpaceNumberOfEntries(userSpaceName);

			// step 3: get member names into Vector
			members = new ArrayList();
			pcml.setValue("qusrtvusmbr.userSpace", userSpaceName);
			size = pcml.getOutputsize("qusrtvusmbr.receiver");
			pcml.setValue("qusrtvusmbr.length", new Integer(size));
			for (int i = listOffset, j = 0; j < listEntries; i += size, j++) {
				pcml.setValue("qusrtvusmbr.startPos", new Integer(i));
				callPcml("qusrtvusmbr");
				member = new Member(this, library, file,
						(String) pcml
								.getValue("qusrtvusmbr.receiver.memberName"),
						(String) pcml
								.getValue("qusrtvusmbr.receiver.sourceType"),
						(String) pcml
								.getValue("qusrtvusmbr.receiver.description"),
						(String) pcml
								.getValue("qusrtvusmbr.receiver.createDate"),
						(String) pcml
								.getValue("qusrtvusmbr.receiver.changeDate"));
				members.add(member);
			}
			return members;
		}
	}

	public ArrayList listObjects(String library, String type) throws Exception {
		String userSpaceName;
		int listOffset, listEntries, size;
		ArrayList list;
		BindingDirectory bd;

		if (connected == false) {
			throw new Exception("Invalid State: DISCONNECTED.");
		}

		list = new ArrayList();
		userSpaceName = createName(library.toUpperCase(), "QTEMP");
		synchronized (pcmlLock) {
			createUserSpace(userSpaceName, 2024 * 2024);

			// retrieve the list of binding directories into the user space on
			// the as400.
			pcml.setValue("quslobj.userSpace", userSpaceName);
			pcml.setValue("quslobj.objectName", createName("*ALL", library));
			pcml.setValue("quslobj.objectType", type);
			callPcml("quslobj");

			listOffset = getUserSpaceListOffset(userSpaceName);
			listEntries = getUserSpaceNumberOfEntries(userSpaceName);

			pcml.setValue("qusrtvusOBJL0100.userSpace", userSpaceName);
			size = pcml.getOutputsize("qusrtvusOBJL0100.receiver");
			pcml.setValue("qusrtvusOBJL0100.length", new Integer(size));
			for (int i = listOffset, j = 0; j < listEntries; i += size, j++) {
				pcml.setValue("qusrtvusOBJL0100.startPos", new Integer(i));
				callPcml("qusrtvusOBJL0100");
				bd = new BindingDirectory(this,
						(String) pcml
								.getValue("qusrtvusOBJL0100.receiver.name"),
						(String) pcml
								.getValue("qusrtvusOBJL0100.receiver.library"),
						(String) pcml
								.getValue("qusrtvusOBJL0100.receiver.type"));
				list.add(bd);
			}
		}
		return list;
	}

	public ArrayList listModuleProcedures(String library, String module)
			throws Exception {
		String userSpaceName;
		int listOffset, listEntries, size, procOffset;
		ArrayList list;

		if (connected == false) {
			throw new Exception("Invalid State: DISCONNECTED.");
		}

		list = new ArrayList();
		userSpaceName = createName(module.toUpperCase(), "QTEMP");
		synchronized (pcmlLock) {
			createUserSpace(userSpaceName, 2024 * 2024);

			// retrieve the list of binding directories into the user space on
			// the as400.
			pcml.setValue("QBNLMODI_MODL0300.userSpace", userSpaceName);
			pcml.setValue("QBNLMODI_MODL0300.module",
					createName(module.toUpperCase(), library.toUpperCase()));
			callPcml("QBNLMODI_MODL0300");

			listOffset = getUserSpaceListOffset(userSpaceName);
			listEntries = getUserSpaceNumberOfEntries(userSpaceName);

			pcml.setValue("qusrtvusMODL0300.userSpace", userSpaceName);
			size = pcml.getOutputsize("qusrtvusMODL0300.receiver");
			pcml.setValue("qusrtvusMODL0300.length", new Integer(size));
			for (int i = listOffset, j = 0; j < listEntries; i += size, j++) {
				// get the list of procedure information.
				pcml.setValue("qusrtvusMODL0300.startPos", new Integer(i));
				callPcml("qusrtvusMODL0300");
				// must get size of this entry to get to the next entry.
				size = ((Integer) pcml
						.getValue("qusrtvusMODL0300.receiver.size")).intValue();

				// get the procedure name.
				procOffset = ((Integer) pcml
						.getValue("qusrtvusMODL0300.receiver.procOffset"))
						.intValue();
				pcml.setValue("qusrtvus2.userSpace", userSpaceName);
				pcml.setValue("qusrtvus2.startPos", new Integer(procOffset + 1));
				pcml.setValue("qusrtvus2.length",
						pcml.getValue("qusrtvusMODL0300.receiver.procLength"));
				callPcml("qusrtvus2");
				list.add(pcml.getValue("qusrtvus2.receiver"));
			}
		}
		return list;
	}

	public ArrayList listServiceProgramModules(String library,
			String serviceProgram) throws Exception {
		String userSpaceName;
		int listOffset, listEntries, size;
		Module module;
		ArrayList list;

		if (connected == false) {
			throw new Exception("Invalid State: DISCONNECTED.");
		}

		list = new ArrayList();
		userSpaceName = createName(serviceProgram.toUpperCase(), "QTEMP");
		synchronized (pcmlLock) {
			createUserSpace(userSpaceName, 2024 * 2024);

			// retrieve the list of binding directories into the user space on
			// the as400.
			pcml.setValue("QBNLSPGM_SPGL0100.userSpace", userSpaceName);
			pcml.setValue("QBNLSPGM_SPGL0100.serviceProgram",
					createName(serviceProgram.toUpperCase(), library));
			callPcml("QBNLSPGM_SPGL0100");

			listOffset = getUserSpaceListOffset(userSpaceName);
			listEntries = getUserSpaceNumberOfEntries(userSpaceName);

			pcml.setValue("qusrtvusSPGL0100.userSpace", userSpaceName);
			size = pcml.getOutputsize("qusrtvusSPGL0100.receiver");
			pcml.setValue("qusrtvusSPGL0100.length", new Integer(size));
			for (int i = listOffset, j = 0; j < listEntries; i += size, j++) {
				pcml.setValue("qusrtvusSPGL0100.startPos", new Integer(i));
				callPcml("qusrtvusSPGL0100");
				module = new Module(
						this,
						(String) pcml
								.getValue("qusrtvusSPGL0100.receiver.moduleLibrary"),
						(String) pcml
								.getValue("qusrtvusSPGL0100.receiver.moduleName"),
						(String) pcml
								.getValue("qusrtvusSPGL0100.receiver.sourceLibrary"),
						(String) pcml
								.getValue("qusrtvusSPGL0100.receiver.sourceFile"),
						(String) pcml
								.getValue("qusrtvusSPGL0100.receiver.sourceMember"));
				list.add(module);
			}
		}
		return list;
	}

	public ArrayList listEntries(BindingDirectory bd) throws Exception {
		ArrayList list;
		Connection connection;
		Statement stmt;
		ResultSet rs;

		list = new ArrayList();
		connection = getConnection();
		stmt = connection.createStatement();
		stmt.execute(buildSqlForCmd("DSPBNDDIR BNDDIR(" + bd.getLibrary() + "/"
				+ bd.getName() + ") OUTPUT(*OUTFILE) OUTFILE(QTEMP/"
				+ bd.getName() + ") OUTMBR(*FIRST *REPLACE)"));
		rs = stmt.executeQuery("SELECT * FROM QTEMP/" + bd.getName());
		while (rs.next()) {
			list.add(new BindingDirectoryEntry(bd, rs.getString(4), rs
					.getString(5), rs.getString(6)));
		}
		rs.close();
		stmt.close();
		return list;
	}

	/**
	 * The temporary source table is used for uploading source code in bulk to
	 * the as400 so that saving source code is faster.
	 * 
	 * @throws SQLException
	 */
	private void acreateTempSrcTable() throws SQLException {
		if (tempSrcTableCreated) {
			return;
		}
		Connection connection;
		Statement stmt;

		connection = getConnection();
		synchronized (connection) {
			stmt = connection.createStatement();
			stmt.execute(buildSqlForCmd("QSYS/CRTSRCPF FILE(QTEMP/SRCUPLOAD)"));
			stmt.execute(buildSqlForCmd("QSYS/ADDPFM FILE(QTEMP/SRCUPLOAD) MBR(SOURCE)"));
			stmt.close();
			tempSrcTableCreated = true;
		}
	}

	/**
	 * checks to see if the stored procedure for upload source code is on the
	 * system. if not, it creates it.
	 * 
	 * @throws SQLException
	 */
	private void acreateUploadProc() throws SQLException {
		System.out.println("creating procedures");
		if (uploadProcedureExists) {
			return;
		}
		Connection connection;
		Statement stmt;
		ResultSet rs;

		connection = getConnection();
		synchronized (connection) {
			stmt = connection.createStatement();
			// determine if it already exists.
			rs = stmt
					.executeQuery("SELECT * FROM qsys2/sysprocs WHERE SPECIFIC_SCHEMA = 'QGPL' and SPECIFIC_NAME = 'PRCUPLOAD'");
			if (rs.next()) {
				// exists.
			} else {
				// doesnt exist.
			}
			try {
				stmt.execute("DROP PROCEDURE QGPL/PRCUPLOAD");
			} catch (Exception e) {
			}
			// check first to see if it exists.
			stmt.execute("CREATE PROCEDURE QGPL/PRCUPLOAD (IN SOURCE VARCHAR(32700), "
					+ "IN CRLF VARCHAR(2), "
					+ "IN APPEND VARCHAR(1)) LANGUAGE SQL "
					+ "SET OPTION DBGVIEW=*SOURCE "
					+ "BEGIN "
					+ "DECLARE startt INTEGER DEFAULT 1; "
					+ "DECLARE found INTEGER DEFAULT 0; "
					+ "DECLARE status INTEGER DEFAULT 0; "
					+ "DECLARE countt INTEGER DEFAULT 0; "
					+ "DECLARE ttoday INTEGER DEFAULT 0; "
					+ "DECLARE src VARCHAR(100); "
					+ "DECLARE tmp VARCHAR(255); "
					+ "DECLARE msg VARCHAR(255); "
					+
					// "SET CRLF=X'0D'; "+
					"IF APPEND = 'T' THEN "
					+ "   SET countt = (SELECT MAX(SRCSEQ) FROM QTEMP/SRCUPLOAD); "
					+ "ELSE "
					+ "   DELETE FROM QTEMP/SRCUPLOAD; "
					+ "END IF; "
					+ "SET found = LOCATE(CRLF, source, startt); "
					+ "WHILE found > 0 DO "
					+ "   IF status = 0 THEN "
					+ "      SET countt = countt + 1; "
					+ "      SET src = SUBSTR(SOURCE, startt, (found - startt)); "
					+ "      SET status = 1; "
					+ "   ELSE "
					+ "      SET tmp = SUBSTR(SOURCE, startt, (found-startt));"
					+ "      SET ttoday = INTEGER(tmp); "
					+ "      INSERT INTO QTEMP/SRCUPLOAD(SRCSEQ, SRCDAT, SRCDTA) "
					+ "      VALUES(countt, ttoday, src); "
					+ "      SET status = 0; "
					+ "   END IF; "
					+ "   SET startt = startt + (found - startt) + LENGTH(CRLF); "
					+ "   SET found = LOCATE(CRLF, source, startt); "
					+ "END WHILE; " + "END");
			stmt.close();
			uploadProcedureExists = true;
		}
		System.out.println("created procedure");
	}

	private boolean uploadProcExists() throws SQLException {
		if (uploadProcedureExists) {
			return true;
		}
		Connection connection;
		Statement stmt;
		ResultSet rs;

		connection = getConnection();
		synchronized (connection) {
			stmt = connection.createStatement();
			// determine if it already exists.
			rs = stmt
					.executeQuery("SELECT * FROM qsys2/sysprocs WHERE SPECIFIC_SCHEMA = 'QGPL' and SPECIFIC_NAME = 'PRCUPLOAD'");
			if (rs.next()) {
				// exists.
				uploadProcedureExists = true;
			} else {
				// doesnt exist.
				uploadProcedureExists = false;
			}
			rs.close();
			stmt.close();
		}
		return uploadProcedureExists;
	}

	/**
	 * Returns a list of libraries that have source files.
	 * 
	 * @throws SQLException
	 * @return ArrayList contains String objects.
	 */
	public ArrayList getSourceLibraries() throws SQLException {
		Connection connection;
		Statement stmt;
		ResultSet rs;
		ArrayList list;

		list = new ArrayList();
		connection = getConnection();
		// get a list of libraries.
		synchronized (connection) {
			stmt = connection.createStatement();
			rs = stmt
					.executeQuery("select distinct TABLE_SCHEMA from qsys2/systables where FILE_TYPE = 'S' order by TABLE_SCHEMA");
			while (rs.next()) {
				list.add(rs.getString(1));
			}
			rs.close();
			stmt.close();
		}
		return list;
	}

	/**
	 * returns libraries that the file exist in.
	 * 
	 * @param fileName
	 *            String
	 * @return ArrayList
	 */
	public ArrayList getLibraries(String fileName) throws SQLException {
		Connection cn;
		Statement stmt;
		ResultSet rs;
		ArrayList list;

		list = new ArrayList();
		cn = getConnection();
		synchronized (cn) {
			stmt = cn.createStatement();
			rs = stmt
					.executeQuery("select distinct system_table_schema from qsys2/systables where system_table_name = '"
							+ fileName.toUpperCase() + "' order by system_table_schema");
			while (rs.next()) {
				list.add(rs.getString(1).trim());
			}
			rs.close();
			stmt.close();
		}
		return list;
	}

	/**
	 * Returns a list of libraries.
	 * 
	 * @throws SQLException
	 * @return ArrayList contains String objects.
	 */
	public ArrayList getLibraries() throws SQLException {
		Connection connection;
		Statement stmt;
		ResultSet rs;
		ArrayList list;

		list = new ArrayList();
		connection = getConnection();
		// get a list of libraries.
		synchronized (connection) {
			stmt = connection.createStatement();
			rs = stmt
					.executeQuery("select distinct TABLE_SCHEMA from qsys2/systables order by TABLE_SCHEMA");
			while (rs.next()) {
				list.add(rs.getString(1));
			}
			rs.close();
			stmt.close();
		}
		return list;
	}

	/**
	 * Returns a list of source files for the given library.
	 * 
	 * @param library
	 *            String The library to look for source files in.
	 * @throws SQLException
	 * @return ArrayList contains String objects;
	 */
	public ArrayList getSourceFiles(String library) throws SQLException {
		Connection connection;
		Statement stmt;
		ResultSet rs;
		ArrayList list;

		list = new ArrayList();
		connection = getConnection();
		synchronized (connection) {
			// get a list of libraries.
			stmt = connection.createStatement();
			rs = stmt
					.executeQuery("select distinct TABLE_NAME from qsys2/systables where FILE_TYPE = 'S' and TABLE_SCHEMA = '"
							+ library + "' order by TABLE_NAME");
			while (rs.next()) {
				list.add(rs.getString(1));
			}
			rs.close();
			stmt.close();
		}
		return list;
	}

	/**
	 * Returns a list of files for the given library. For each table, there will
	 * be 3 strings in the array list. The first string is the name of the
	 * table. The second string is the FILE_TYPE. The third string is the
	 * TABLE_TYPE. FILE_TYPE can be D or S. D = Data file type. S = Source file
	 * type. TABLE_TYPE can be A, L, P, T, or V. A = Alias. (From a create alias
	 * sql command) L = DDS Logical file. P = DDS Physical file. T = Table. (I
	 * think from a create table sql command?) V = View. (I think from a create
	 * view sql command?)
	 * 
	 * @param library
	 *            String The library to look source files in.
	 * @throws SQLException
	 * @return ArrayList contains String objects;
	 */
	public ArrayList getFiles(String library) throws SQLException {
		Connection connection;
		Statement stmt;
		ResultSet rs;
		ArrayList list;

		list = new ArrayList();
		connection = getConnection();
		synchronized (connection) {
			// get a list of libraries.
			stmt = connection.createStatement();
			rs = stmt
					.executeQuery("select distinct TABLE_NAME, FILE_TYPE, TABLE_TYPE from qsys2/systables where TABLE_SCHEMA = '"
							+ library + "'");
			while (rs.next()) {
				list.add(rs.getString(1));
				list.add(rs.getString(2));
				list.add(rs.getString(3));
			}
			rs.close();
			stmt.close();
		}
		return list;
	}

	public void copyTo(String fromLib, String fromFile, String toLib,
			String toFile) throws Exception {
		copyTo(fromLib, fromFile, this, toLib, toFile);
	}

	public void copyTo(String fromLib, String fromFile, AS400System toSystem,
			String toLib, String toFile) throws Exception {
		// if copying to different system.
		if (equals(toSystem) == false) {
			toSystem.call("CRTDDMF FILE(QTEMP/DDMTEMP) RMTFILE(" + fromLib
					+ "/" + fromFile + ") RMTLOCNAME('" + address + "' *IP)");
			toSystem.call("CPYF FROMFILE(QTEMP/DDMTEMP) TOFILE(" + toLib + "/"
					+ toFile
					+ ") CRTFILE(*YES) FMTOPT(*NOCHK) MBROPT(*REPLACE)");
		} else {
			call("CPYF FROMFILE(" + fromLib + "/" + fromFile + ") TOFILE("
					+ toLib + "/" + toFile
					+ ") CRTFILE(*YES) FMTOPT(*NOCHK) MBROPT(*REPLACE)");
		}
	}

	/**
	 * Searches for a string in the source member specified.
	 * 
	 * @param library
	 *            String The library to search in.
	 * @param file
	 *            String The file to search in.
	 * @param member
	 *            String The source member to search in.
	 * @param term
	 *            String The term/text to search for.
	 * @param matchCase
	 *            boolean If true then the case of the term specified must match
	 *            the case of the term in the source member.
	 * @throws SQLException
	 * @return ArrayList contains String objects.
	 */
	public ArrayList search(String library, String file, String member,
			String term, boolean matchCase) throws SQLException {
		Connection connection;
		Statement stmt;
		ResultSet rs;
		ArrayList list;
		int a;
		Properties p = new Properties();
		String clause;

		synchronized (this) {
			alias++;
			a = alias;
		}
		list = new ArrayList();
		if (matchCase) {
			clause = " where srcdta like '%" + term + "%'";
		} else {
			clause = " where ucase(srcdta) like '%" + term.toUpperCase() + "%'";
		}
		connection = getConnection();
		synchronized (connection) {
			stmt = connection.createStatement();
			stmt.execute("create alias qtemp/s" + a + " for " + library + "/"
					+ file + "(" + member + ")");
			rs = stmt.executeQuery("select * from qtemp/s" + a + clause);
			while (rs.next()) {
				list.add(rs.getString(3));
			}
			rs.close();
			stmt.execute("drop alias qtemp/s" + a);
			stmt.close();
		}
		return list;
	}

	/**
	 * Gets the source code for the library, file, member specified. Calls back
	 * to the source loader object for each line loaded. The copy id returned is
	 * used when the source is saved after changes are made. The changes are
	 * updated to the copy, the deleted lines are removed, and the new lines are
	 * appended. The copy file is then inserted into the original with the lines
	 * sorted by their number. This improves performance by not writing every
	 * line to the original file.
	 * 
	 * @param library
	 *            String The library name of the source member.
	 * @param file
	 *            String The file name of the source member.
	 * @param member
	 *            String The name of the source member.
	 * @param sourceLoader
	 *            SourceLoader Receives an event for each line that is loaded
	 *            from the line.
	 * @throws SQLException
	 * @return int the id of the file in qtemp that is a copy of the source that
	 *         is loaded.
	 */
	protected int getSource(String library, String file, String member,
			SourceLoader sourceLoader) throws SQLException {
		Connection connection;
		Statement stmt;
		ResultSet rs;
		int a;
		String text;

		connection = getConnection();
		synchronized (this) {
			alias++;
			a = alias;
		}
		synchronized (connection) {
			stmt = connection.createStatement();
			// used to copy the source from.
			// stmt.execute("create alias qtemp/a" + a + " for " + library + "/"
			// + file + "(" + member + ")");
			try {
				stmt.execute("drop alias qtemp/" + member);
			} catch (Exception e) {
			}
			stmt.execute("create alias qtemp/" + member + " for " + library
					+ "/" + file + "(" + member + ")");
			// rs = stmt.executeQuery("select * from qtemp/a" + a);
			rs = stmt.executeQuery("select * from qtemp/" + member);
			while (rs.next()) {
				text = rs.getString(3);
				sourceLoader.lineLoaded(rs.getFloat(1), rs.getInt(2), text);
			}
			rs.close();
			// stmt.execute("drop alias qtemp/a" + a);
			stmt.execute("drop alias qtemp/" + member);
			stmt.close();
		}
		return a;
	}

	/**
	 * Gets the error text that results from compiling a source member with the
	 * OPTION(*EVENTF) option. So, the file passed in will not match the file
	 * that the source member was in when the compile occured. The file will be
	 * EVFEVENT. This is not hardcoded now, but proably will be in future. The
	 * path is used for preppending the "library file member" to the error line
	 * and description that is displayed in the compile results tab on the main
	 * window.
	 * 
	 * @param library
	 *            String The library that contains the error text file.
	 * @param file
	 *            String The file that contains the error source member.
	 * @param member
	 *            String The name of the source member that contains the errors.
	 * @param path
	 *            String The library/file/member that was compiled to produce
	 *            the errors.
	 * @throws SQLException
	 * @return String The compiler error text.
	 */
	public String getErrorText(String library, String file, String member,
			String path) throws SQLException {
		Connection connection;
		Statement stmt;
		ResultSet rs;
		ResultSetMetaData meta;
		String buffer;
		StringBuffer text;
		int i, a;

		synchronized (this) {
			alias++;
			a = alias;
		}
		text = new StringBuffer("");
		connection = getConnection();
		synchronized (connection) {
			stmt = connection.createStatement();
			stmt.execute("create alias qtemp/text" + a + " for " + library
					+ "/" + file + "(" + member + ")");
			rs = stmt.executeQuery("select * from qtemp/text" + a);
			while (rs.next()) {
				buffer = rs.getString(1);
				if (buffer.startsWith("ERROR")) {
					try {
						i = Integer.parseInt(buffer.substring(58, 59));
						if (i > 0) {
							text.append("ERROR ");
							if (path != null) {
								text.append(path);
								text.append(" ");
							}
							text.append(buffer.substring(5).trim());
							text.append("\n");
						}
					} catch (Exception e) {
					}
				}
			}
			rs.close();
			stmt.execute("drop alias qtemp/text" + a);
			stmt.close();
		}
		return text.toString();
	}

	/**
	 * 
	 * @param library
	 *            String
	 * @param file
	 *            String
	 * @param member
	 *            String
	 * @param content
	 *            String
	 * @throws SQLException
	 * @return String
	 */
	public String indexLineNumbers(String library, String file, String member,
			String content) throws SQLException {
		int[] index;
		int start, length, numbersStart;
		StringBuffer buffer;

		buffer = new StringBuffer(content);
		length = content.length();
		if (length < 5) {
			return buffer.toString();
		}
		index = getIndex(library, file, member);
		numbersStart = -1;
		start = 0;
		while (true) {
			if (content.substring(start, start + 5).equals("ERROR")) {
				// this line is an error message.
				// if this is the first error message line, find the place where
				// the numbers that need to be replaced start.
				if (numbersStart == -1) {
					numbersStart = findNumbersStart(content, start);
					if (numbersStart == -1) {
						// couldnt find the place where the numbers start, no
						// need to index, return what we have.
						return buffer.toString();
					}
				}
				replace(buffer, content, index, start + numbersStart);
				replace(buffer, content, index, start + numbersStart + 7);
				replace(buffer, content, index, start + numbersStart + 18);
			}
			start = content.indexOf("\n", start + 1);
			if (start == -1 || start + 5 >= length) {
				break;
			}
			start++;
		}
		return buffer.toString();
	}

	/**
	 * output from compiling a SQLRPGLE doesnt have the line numbers the same as
	 * the source code listing, so this builds an index that can be used to
	 * translate the numbers in the SQLRPGLE compile listing to the line numbers
	 * in the source listing.
	 * 
	 * @param library
	 *            String
	 * @param file
	 *            String
	 * @param member
	 *            String
	 * @throws SQLException
	 * @return int[]
	 */
	public int[] getIndex(String library, String file, String member)
			throws SQLException {
		Connection connection;
		Statement stmt;
		ResultSet rs;
		ArrayList list = new ArrayList();
		int[] index;
		int a;

		connection = getConnection();
		synchronized (this) {
			alias++;
			a = alias;
		}
		synchronized (connection) {
			stmt = connection.createStatement();
			stmt.execute("create alias qtemp/a" + a + " for " + library + "/"
					+ file + "(" + member + ")");
			rs = stmt.executeQuery("select * from qtemp/a" + a);
			while (rs.next()) {
				list.add(new Integer((int) Float.parseFloat(rs.getString(1))));
			}
			rs.close();
			stmt.execute("drop alias qtemp/a" + a);
			stmt.close();
		}
		index = new int[list.size()];
		for (int x = 0, next = 1; x < list.size(); x++) {
			index[x] = ((Integer) list.get(x)).intValue();
			if (index[x] != next) {
				index[x] = index[x - 1];
			} else {
				next++;
			}
		}
		return index;
	}

	private int findNumbersStart(String content, int start) {
		int end, count;

		// find the place where the numbers start.
		end = content.indexOf("\n", start);
		if (end == -1) {
			end = content.length();
		}
		count = 0;
		while (true) {
			start = content.indexOf(" ", start + 1);
			if (start == -1) {
				return -1;
			}
			count++;
			if (count == 8) {
				return start + 1;
			}
		}
	}

	/**
	 * 
	 * @param buffer
	 *            StringBuffer
	 * @param content
	 *            String
	 * @param index
	 *            int[]
	 * @param start
	 *            int
	 */
	private void replace(StringBuffer buffer, String content, int[] index,
			int start) {
		int line;
		String replace;

		line = Integer.parseInt(content.substring(start, start + 6));
		if (line == 0) {
			return;
		}
		line = index[line - 1];
		replace = Integer.toString(line);
		while (replace.length() < 6) {
			replace = "0" + replace;
		}
		buffer.replace(start, start + 6, replace);
	}

	public Member createMember(String library, String file, String member,
			String type) throws Exception {
		call("QSYS/ADDPFM FILE(" + library + "/" + file + ") MBR(" + member
				+ ") SRCTYPE(" + type + ")");
		return new Member(this, library, file, member);
	}

	public void call(String cmd) throws Exception {
		try {
			Environment.qcmdexec.appendLine(cmd);
			commandCall.run(cmd);
			Environment.qcmdexec.append(commandCall.getMessageList());
		} catch (Exception e) {
			Environment.qcmdexec.appendLine(e.getMessage());
			throw e;
		}
	}

	public String buildSqlForCmd(String cmd) {
		String length;

		cmd = cmd.trim();
		length = Integer.toString(cmd.length());
		while (length.length() < 10) {
			length = "0" + length;
		}
		return "CALL QSYS/QCMDEXC('" + cmd + "', " + length + ".00000)";
	}

	/**
	 * calls the as400 command through the sql connection.
	 * 
	 * @param cmd
	 *            String
	 * @throws Exception
	 */
	public void sqlCall(String cmd) throws Exception {
		Environment.sql.executeSQL(buildSqlForCmd(cmd));
	}

	/**
	 * Calls the pcml program and throws an exception if there is a problem.
	 */
	private void callPcml(String api) throws Exception {
		AS400Message[] msgs;
		StringBuffer buffer;

		if (pcml.callProgram(api) == false) {
			msgs = pcml.getMessageList(api);
			buffer = new StringBuffer();
			for (int i = 0; i < msgs.length; i++) {
				buffer.append(msgs[i].getID());
				buffer.append(": ");
				buffer.append(msgs[i].getText());
			}
			throw new Exception(buffer.toString());
		}
	}

	public boolean isCheckBox() {
		return true;
	}

	public Node getParent() {
		return null;
	}

	public Icon getIcon() {
		return Icons.iconScreen;
	}

	public String toString() {
		if (name.trim().length() == 0) {
			return address;
		}
		return name;
	}

	public void dispose() {
		disconnect();
		pcml = null;
		as400 = null;
		name = null;
		address = null;
		user = null;
		password = null;
	}
}
