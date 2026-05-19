public void onExecute() throws Exception {
  // Create an instance of the inner class Runnable and
  // submit it to the job service
  MyTask task = new MyTask();
  task.submit();
}

public class MyTask implements Runnable {
  private static final String CSV_DIR_ORD = "file:^csv";
  private static final String MODBUS_NETWORK_ORD = "station:|slot:/Drivers/ModbusTcpSlaveNetwork";
  private static final String SOURCE_QUERY_BASE_ORD = "station:|slot:/Drivers/NiagaraNetwork|bql:";
  private static final String POINT_NOT_FOUND_FILE = "PointNotFound.csv";
  private static final String POINT_NOT_FOUND_ORD = CSV_DIR_ORD + "/" + POINT_NOT_FOUND_FILE;

  private static final int DEV_NAME_COL = 0;
  private static final int DEV_NO_COL = 1;
  private static final int REG_TYPE_COL = 2;
  private static final int DATA_TYPE_COL = 3;
  private static final int REG_NO_COL = 4;
  private static final int SRC_ORD_COL = 6;
  private static final int REQUIRED_COLS = SRC_ORD_COL + 1;

  public MyTask() {
    job = new BRunnableJob(this);
  }

  public void submit() {
    job.submit(null);
  }

  public void run() {
    job.log().message("started task [" + Thread.currentThread().getName() + "]");
    ImportStats stats = new ImportStats();
    pointNotFoundRows = new java.util.ArrayList<String>();

    try {
      BDirectory dir = resolveCsvDirectory();
      BIFile[] fileList = dir.listFiles();
      BModbusTcpSlaveNetwork network = resolveNetwork();
      writePointNotFoundFile();

      job.log().message("Found " + fileList.length + " files");
      for (int fileIndex = 0; fileIndex < fileList.length; fileIndex++) {
        if (isPointNotFoundFile(fileList[fileIndex])) {
          job.log().message("Skipping output file: " + fileList[fileIndex].getFilePath());
          continue;
        }

        processFile(network, fileList[fileIndex], fileIndex, fileList.length, stats);
      }

      rebuildRegisterRanges(network);
      writePointNotFoundFile();
      job.progress(100);
      job.log().success("ended task [" + Thread.currentThread().getName() + "] applied=" + stats.applied
          + ", skipped=" + stats.skipped + ", failed=" + stats.failed);
    } catch (Exception e) {
      job.log().failed("Failed to export drawbridge points", e);
    }
  }

  private BDirectory resolveCsvDirectory() throws Exception {
    return (BDirectory) BOrd.make(CSV_DIR_ORD).resolve().get();
  }

  private BModbusTcpSlaveNetwork resolveNetwork() throws Exception {
    return (BModbusTcpSlaveNetwork) BOrd.make(MODBUS_NETWORK_ORD).get();
  }

  private boolean isPointNotFoundFile(BIFile file) {
    String filePath = file.getFilePath().toString().replace('\\', '/');
    return POINT_NOT_FOUND_FILE.equals(filePath) || filePath.endsWith("/" + POINT_NOT_FOUND_FILE);
  }

  private void processFile(BModbusTcpSlaveNetwork network, BIFile file, int fileIndex, int fileCount,
      ImportStats stats) throws Exception {
    job.log().message("Processing: " + file.getFilePath());
    java.util.List<String> rows = readLines(file);

    if (rows.size() == 0) {
      job.log().message("Skipping empty file: " + file.getFilePath());
      updateProgress(fileIndex, fileCount, 1, 1);
      return;
    }

    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      String row = rows.get(rowIndex);
      if (row.trim().length() == 0) {
        stats.skipped++;
        updateProgress(fileIndex, fileCount, rowIndex + 1, rows.size());
        continue;
      }

      try {
        PointImportRow importRow = parseRow(row, rowIndex + 1);
        BControlPoint source = resolveSourcePoint(importRow);
        BModbusTcpSlaveDevice device = getOrCreateDevice(network, importRow);
        BControlPoint point = upsertPoint(device, importRow, source);

        if (point != null) {
          ensureLinks(point, source);
          stats.applied++;
        } else {
          stats.skipped++;
        }
      } catch (Exception e) {
        stats.failed++;
        job.log().failed("Error in " + file.getFilePath() + " line " + (rowIndex + 1) + ": " + row, e);
      }

      updateProgress(fileIndex, fileCount, rowIndex + 1, rows.size());
    }
  }

  private java.util.List<String> readLines(BIFile file) throws Exception {
    java.util.List<String> lines = new java.util.ArrayList<String>();

    try (java.io.BufferedReader reader = new java.io.BufferedReader(
        new java.io.InputStreamReader(file.getInputStream(), "UTF-8"))) {
      String line = reader.readLine();
      while (line != null) {
        lines.add(line);
        line = reader.readLine();
      }
    }

    return lines;
  }

  private PointImportRow parseRow(String row, int lineNumber) {
    String[] columns = parseCsvLine(row);
    if (columns.length < REQUIRED_COLS) {
      throw new IllegalArgumentException("Expected at least " + REQUIRED_COLS + " columns but found " + columns.length
          + ". Data: " + joinColumns(columns));
    }

    String deviceName = trim(columns[DEV_NAME_COL]);
    if (deviceName.length() == 0)
      throw new IllegalArgumentException("Device name is blank");

    int deviceNumber = parseInteger("device number", columns[DEV_NO_COL]);
    int registerNumber = parseInteger("register number", columns[REG_NO_COL]);
    if (registerNumber < 0)
      throw new IllegalArgumentException("Register number cannot be negative: " + registerNumber);

    boolean holdingRegister = parseRegisterType(columns[REG_TYPE_COL]);
    DataTypeEnum dataType = parseDataType(columns[DATA_TYPE_COL], holdingRegister);
    String sourceOrd = trim(columns[SRC_ORD_COL]);
    String pointName = buildPointName(holdingRegister, dataType, registerNumber);

    return new PointImportRow(lineNumber, deviceName, deviceNumber, holdingRegister, dataType, registerNumber,
        sourceOrd, pointName);
  }

  private String[] parseCsvLine(String row) {
    java.util.List<String> values = new java.util.ArrayList<String>();
    StringBuffer value = new StringBuffer();
    boolean inQuotes = false;

    for (int i = 0; i < row.length(); i++) {
      char c = row.charAt(i);
      if (c == '"') {
        if (inQuotes && i + 1 < row.length() && row.charAt(i + 1) == '"') {
          value.append('"');
          i++;
        } else {
          inQuotes = !inQuotes;
        }
      } else if (c == ',' && !inQuotes) {
        values.add(trim(value.toString()));
        value.setLength(0);
      } else {
        value.append(c);
      }
    }

    if (inQuotes)
      throw new IllegalArgumentException("Unclosed quoted CSV value");

    values.add(trim(value.toString()));
    return values.toArray(new String[values.size()]);
  }

  private boolean parseRegisterType(String value) {
    String normalized = lower(value);
    if (normalized.indexOf("holding") >= 0)
      return true;
    if (normalized.indexOf("coil") >= 0)
      return false;

    throw new IllegalArgumentException("Unsupported register type: " + value);
  }

  private DataTypeEnum parseDataType(String value, boolean holdingRegister) {
    if (!holdingRegister)
      return DataTypeEnum.Coil;

    String normalized = lower(value);
    if (normalized.indexOf("float") >= 0)
      return DataTypeEnum.Float;
    if (normalized.indexOf("int") >= 0)
      return DataTypeEnum.Int;

    throw new IllegalArgumentException("Holding register requires Int or Float data type: " + value);
  }

  private int parseInteger(String label, String value) {
    try {
      return Integer.valueOf(trim(value)).intValue();
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid " + label + ": " + value, e);
    }
  }

  private String buildPointName(boolean holdingRegister, DataTypeEnum dataType, int registerNumber) {
    if (!holdingRegister)
      return SlotPath.escape("Coil" + registerNumber);
    if (dataType == DataTypeEnum.Float)
      return SlotPath.escape("Float" + registerNumber);
    if (dataType == DataTypeEnum.Int)
      return SlotPath.escape("Int" + registerNumber);

    throw new IllegalArgumentException("Unsupported point type for register " + registerNumber);
  }

  private BControlPoint resolveSourcePoint(PointImportRow row) throws Exception {
    if (row.hasNullSource()) {
      job.log().message("Found NULL Ord for " + row.pointName + ", creating point without source link");
      return null;
    }

    BControlPoint source = null;
    boolean recordPointNotFound = false;
    try {
      source = findSourcePointByProxy(row.sourceOrd);
      recordPointNotFound = source == null;
    } catch (Exception e) {
      job.log().failed("Could not resolve source by proxy: " + row.sourceOrd, e);
      recordPointNotFound = true;
    }

    if (recordPointNotFound) {
      recordPointNotFound(row.sourceOrd);
      job.log().message("No source point found by proxy for " + row.sourceOrd + "; recorded in "
          + POINT_NOT_FOUND_FILE);
    }

    return source;
  }

  private BControlPoint findSourcePointByProxy(String sourceOrd) throws Exception {
    String bql = SOURCE_QUERY_BASE_ORD + "select slotPath from control:ControlPoint where proxyExt like '"
        + escapeBqlString(sourceOrd) + "'";
    BStation station = Sys.getStation();
    BITable result = (BITable) BOrd.make(bql).get(station);
    ColumnList columns = result.getColumns();
    Column slotPathColumn = columns.get("slotPath");
    if (slotPathColumn == null)
      throw new IllegalStateException("BQL did not return slotPath column for source " + sourceOrd);

    TableCursor cursor = result.cursor();
    while (cursor.next()) {
      String slotPath = cursor.cell(slotPathColumn).toString();
      return (BControlPoint) BOrd.make("station:|" + slotPath).get();
    }

    return null;
  }

  private void recordPointNotFound(String sourceOrd) {
    pointNotFoundRows.add(toCsvValue(sourceOrd));
  }

  private void writePointNotFoundFile() throws Exception {
    BIFile file = (BIFile) BOrd.make(POINT_NOT_FOUND_ORD).resolve().get();

    try (java.io.BufferedWriter writer = new java.io.BufferedWriter(
        new java.io.OutputStreamWriter(file.getOutputStream(), "UTF-8"))) {
      for (int i = 0; i < pointNotFoundRows.size(); i++) {
        writer.write(pointNotFoundRows.get(i));
        writer.newLine();
      }
    }
  }

  private BModbusTcpSlaveDevice getOrCreateDevice(BModbusTcpSlaveNetwork network, PointImportRow row)
      throws Exception {
    BModbusTcpSlaveDevice device = findDevice(network, row.deviceNumber);
    if (device != null)
      return device;

    if (!network.isUniqueDeviceAddress(row.deviceNumber)) {
      throw new IllegalStateException("Device address is already used but no slave device was found: "
          + row.deviceNumber);
    }

    device = new BModbusTcpSlaveDevice();
    device.setDeviceAddress(row.deviceNumber);
    network.add(SlotPath.escape(row.deviceName + "_" + row.deviceNumber), device);
    job.log().message("Created device " + row.deviceName + " address " + row.deviceNumber);
    return device;
  }

  private BModbusTcpSlaveDevice findDevice(BModbusTcpSlaveNetwork network, int deviceNumber) {
    BComponent[] devices = network.getChildComponents();
    for (int i = 0; i < devices.length; i++) {
      BComponent device = devices[i];
      if (device instanceof BModbusTcpSlaveDevice
          && ((BModbusTcpSlaveDevice) device).getDeviceAddress() == deviceNumber) {
        return (BModbusTcpSlaveDevice) device;
      }
    }

    return null;
  }

  private BControlPoint upsertPoint(BModbusTcpSlaveDevice device, PointImportRow row, BControlPoint source)
      throws Exception {
    BModbusServerPointDeviceExt points = device.getPoints();
    BControlPoint existing = findPoint(points, row.pointName);

    if (existing != null) {
      if (!isCompatiblePoint(existing, row)) {
        job.log().message("Skipping existing incompatible point " + existing.getSlotPathOrd() + " for CSV line "
            + row.lineNumber);
        return null;
      }

      configurePoint(existing, row, source);
      job.log().message("Updated existing point " + existing.getSlotPathOrd());
      return existing;
    }

    BControlPoint point = createPoint(row);
    configurePoint(point, row, source);
    points.add(row.pointName, point);
    job.log().message("Created point " + point.getSlotPathOrd());
    return point;
  }

  private BControlPoint findPoint(BModbusServerPointDeviceExt points, String pointName) {
    BComponent[] children = points.getChildComponents();
    for (int i = 0; i < children.length; i++) {
      BComponent child = children[i];
      if (child instanceof BControlPoint && pointName.equals(child.getName()))
        return (BControlPoint) child;
    }

    return null;
  }

  private boolean isCompatiblePoint(BControlPoint point, PointImportRow row) {
    if (row.holdingRegister)
      return point instanceof BNumericWritable;

    return point instanceof BBooleanWritable;
  }

  private BControlPoint createPoint(PointImportRow row) {
    if (row.holdingRegister)
      return new BNumericWritable();

    return new BBooleanWritable();
  }

  private void configurePoint(BControlPoint point, PointImportRow row, BControlPoint source) {
    BFlexAddress address = new BFlexAddress();
    address.setAddress(String.valueOf(row.registerNumber));
    address.setAddressFormat(BAddressFormatEnum.decimal);

    if (row.holdingRegister) {
      BModbusServerNumericProxyExt proxyExt = new BModbusServerNumericProxyExt();
      if (row.dataType == DataTypeEnum.Float)
        proxyExt.setDataType(BDataTypeEnum.floatType);
      else
        proxyExt.setDataType(BDataTypeEnum.integerType);

      proxyExt.setDataAddress(address);
      point.setProxyExt(proxyExt);
    } else {
      BModbusServerBooleanProxyExt proxyExt = new BModbusServerBooleanProxyExt();
      proxyExt.setStatusType(BStatusTypeEnum.coil);
      proxyExt.setDataAddress(address);
      point.setProxyExt(proxyExt);
    }

    if (source != null)
      point.setFacets(source.getFacets());
  }

  private void ensureLinks(BControlPoint point, BControlPoint source) throws Exception {
    if (point.getLinks().length != 0) {
      job.log().message("A link already exists on " + point.getSlotPathOrd());
      return;
    }

    if (source != null) {
      BLink sourceLink = createSourceLink(source, point);
      if (sourceLink != null)
        point.add("sourceLink", sourceLink);
      else
        job.log().message("No converter found for " + source.getSlotPathOrd() + " to " + point.getSlotPathOrd());
    }

    point.add("subscribeLink", new BLink(point.getHandleOrd(), "out", "in16", true));
  }

  private BLink createSourceLink(BControlPoint source, BControlPoint point) throws Exception {
    Type srcSlotType = source.get("out").getType();
    Type destSlotType = point.get("in10").getType();

    if (srcSlotType == destSlotType)
      return new BLink(source.getHandleOrd(), "out", "in10", true);

    BConverter converter = findConverter(srcSlotType, destSlotType);
    if (converter == null)
      return null;

    return new BConversionLink(source.getHandleOrd(), "out", "in10", true, converter);
  }

  private BConverter findConverter(Type srcSlotType, Type destSlotType) throws Exception {
    TypeInfo[] adapters = Sys.getRegistry().getAdapters(srcSlotType.getTypeInfo(), destSlotType.getTypeInfo());
    for (int i = 0; i < adapters.length; i++) {
      Object adapter = adapters[i].getInstance();
      if (adapter instanceof BConverter)
        return (BConverter) adapter;
    }

    return null;
  }

  private void rebuildRegisterRanges(BModbusTcpSlaveNetwork network) {
    BComponent[] devices = network.getChildComponents();
    for (int i = 0; i < devices.length; i++) {
      if (devices[i] instanceof BModbusTcpSlaveDevice)
        rebuildRegisterRanges((BModbusTcpSlaveDevice) devices[i]);
    }
  }

  private void rebuildRegisterRanges(BModbusTcpSlaveDevice device) {
    int maxCoil = -1;
    int maxHolding = -1;
    BComponent[] points = device.getPoints().getChildComponents();

    for (int i = 0; i < points.length; i++) {
      if (!(points[i] instanceof BControlPoint))
        continue;

      BControlPoint point = (BControlPoint) points[i];
      BAbstractProxyExt ext = point.getProxyExt();

      try {
        if (ext instanceof BModbusServerBooleanProxyExt) {
          BModbusServerBooleanProxyExt boolExt = (BModbusServerBooleanProxyExt) ext;
          int address = Integer.valueOf(boolExt.getDataAddress().getAddress()).intValue();
          if (address > maxCoil)
            maxCoil = address;
        } else if (ext instanceof BModbusServerNumericProxyExt) {
          BModbusServerNumericProxyExt numExt = (BModbusServerNumericProxyExt) ext;
          int address = Integer.valueOf(numExt.getDataAddress().getAddress()).intValue();
          int lastAddress = usesTwoRegisters(numExt) ? address + 1 : address;
          if (lastAddress > maxHolding)
            maxHolding = lastAddress;
        }
      } catch (Exception e) {
        job.log().failed("Invalid Modbus address on " + point.getSlotPathOrd(), e);
      }
    }

    device.getValidInputRegistersRange().clear();
    device.getValidStatusRange().clear();
    device.getValidHoldingRegistersRange().clear();
    if (maxHolding >= 0)
      device.getValidHoldingRegistersRange().addRange(createRegisterRange(maxHolding));

    device.getValidCoilsRange().clear();
    if (maxCoil >= 0)
      device.getValidCoilsRange().addRange(createRegisterRange(maxCoil));
  }

  private boolean usesTwoRegisters(BModbusServerNumericProxyExt ext) {
    return ext.getDataType() == BDataTypeEnum.floatType
        || ext.getDataType() == BDataTypeEnum.longType
        || ext.getDataType() == BDataTypeEnum.unsignedLong;
  }

  private BModbusRegisterRangeEntry createRegisterRange(int maxAddress) {
    BModbusRegisterRangeEntry range = new BModbusRegisterRangeEntry();
    range.setCriticalData(false);
    range.setEnabled(true);
    range.setStartingAddressOffset(1);
    range.setSize(maxAddress + 1);
    return range;
  }

  private void updateProgress(int fileIndex, int fileCount, int currentRow, int rowCount) {
    if (fileCount == 0) {
      job.progress(100);
      return;
    }

    int fileProgress = (currentRow * 100) / rowCount;
    int totalProgress = ((fileIndex * 100) + fileProgress) / fileCount;
    if (totalProgress > 99)
      totalProgress = 99;

    job.progress(totalProgress);
  }

  private String escapeBqlString(String value) {
    return value.replace("'", "''");
  }

  private String joinColumns(String[] columns) {
    StringBuffer buffer = new StringBuffer();
    for (int i = 0; i < columns.length; i++) {
      if (i > 0)
        buffer.append(";");
      buffer.append(columns[i]);
    }

    return buffer.toString();
  }

  private String toCsvValue(String value) {
    String safeValue = trim(value);
    if (safeValue.indexOf(',') < 0 && safeValue.indexOf('"') < 0 && safeValue.indexOf('\n') < 0
        && safeValue.indexOf('\r') < 0)
      return safeValue;

    return "\"" + safeValue.replace("\"", "\"\"") + "\"";
  }

  private String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private String lower(String value) {
    return trim(value).toLowerCase(java.util.Locale.ENGLISH);
  }

  private class PointImportRow {
    private final int lineNumber;
    private final String deviceName;
    private final int deviceNumber;
    private final boolean holdingRegister;
    private final DataTypeEnum dataType;
    private final int registerNumber;
    private final String sourceOrd;
    private final String pointName;

    private PointImportRow(int lineNumber, String deviceName, int deviceNumber, boolean holdingRegister,
        DataTypeEnum dataType, int registerNumber, String sourceOrd, String pointName) {
      this.lineNumber = lineNumber;
      this.deviceName = deviceName;
      this.deviceNumber = deviceNumber;
      this.holdingRegister = holdingRegister;
      this.dataType = dataType;
      this.registerNumber = registerNumber;
      this.sourceOrd = sourceOrd;
      this.pointName = pointName;
    }

    private boolean hasNullSource() {
      String normalized = lower(sourceOrd);
      return normalized.length() == 0 || normalized.indexOf("null") >= 0;
    }
  }

  private class ImportStats {
    private int applied;
    private int skipped;
    private int failed;
  }

  private BRunnableJob job;
  private java.util.List<String> pointNotFoundRows;
}

public enum DataTypeEnum {
  Int, Float, Coil
}
