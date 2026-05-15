public void onExecute() throws Exception {
  // Create an instance of the inner class Runnable and
  // submit it to the job service
  MyTask task = new MyTask();
  task.submit();
}

public class MyTask implements Runnable {
  public MyTask() {
    job = new BRunnableJob(this);
  }

  public void submit() {
    job.submit(null);
  }

  public void run() {
    job.log().message("started task [" + Thread.currentThread().getName() + "]");
    try {
      BOrd dirOrd = BOrd.make("file:^csv"); // set file path here as required
      BDirectory dir = (BDirectory) dirOrd.resolve().get();
      BIFile[] fileList = dir.listFiles();// find all files in the specified directory

      job.log().message("Found " + fileList.length + " files");
      for (int a = 0; a < fileList.length; a++) {
        job.log().message("Processing: " + fileList[a].getFilePath());
        InputStreamReader reader = new InputStreamReader(fileList[a].getInputStream());
        String[] rows = FileUtil.readLines(reader);

        BOrd srcOrd; // source ord
        int srcOrdCol = 6; // source ord column number
        int devNo; // device number
        int devNoCol = 1; // device number column number
        String devName; // device name
        int devNameCol = 0; // device name column number
        int regNo; // register number
        int regNoCol = 4; // register number column number
        boolean regType; // register type (true = holding or false = coil)
        int regTypeCol = 2; // regType column number
        DataTypeEnum dataType; // data type (Int16, Float, 'blank' (Coil))
        int dataTypeCol = 3;
        BControlPoint src;
        String[] rowArray = null;

        // get network - we don't do any checks here, just assume the name is correct
        BModbusTcpSlaveNetwork network = (BModbusTcpSlaveNetwork) BOrd
            .make("station:|slot:/Drivers/ModbusTcpSlaveNetwork").get();

        // iterate through the rows and process
        for (int i = 0; i < rows.length; i++) {
          try {
            // create info needed
            rowArray = rows[i].split(",");
            src = null;
            if (rowArray[srcOrdCol].toLowerCase().contains("null")) {

              job.log().message("Found 'NULL' Ord, i'll create a dummy point");
            } else {
              // ==================== 1. Define your BQL query ====================
              String bql = "station:|slot:/Drivers/NiagaraNetwork|bql:" +
                  "select slotPath from control:ControlPoint " +
                  "where proxyExt like '" + rowArray[srcOrdCol] + "'";

              // ==================== 2. Execute BQL to obtain BITable ====================
              BStation station = Sys.getStation();
              BOrd queryOrd = BOrd.make(bql);
              BITable result = (BITable) queryOrd.get(station);

              // Get slotPath column
              ColumnList columns = result.getColumns();
              Column slotPathColumn = columns.get("slotPath"); // column name is slotPath
              job.log().message(slotPathColumn.getName());

              // ==================== 3. Iterate through results and retrieve ControlPoint
              // using slotPath ====================
              TableCursor cursor = result.cursor();
              int count = 0;

              while (cursor.next()) {
                // Retrieve slotPath (BSlotPath type)
                String slotPath = cursor.cell(slotPathColumn).toString();

                // Construct the complete ORD (station:|slot:/...)
                String fullOrdStr = "station:|" + slotPath;
                BOrd cpOrd = BOrd.make(fullOrdStr);

                // Resolve the ControlPoint component
                src = (BControlPoint) cpOrd.get();
                break;
              }

              // srcOrd =
              // BOrd.make(TextUtil.trimRight(TextUtil.trimLeft(rowArray[srcOrdCol])));
              // src = (BControlPoint)srcOrd.get();
            }
            devNo = Integer.valueOf(rowArray[devNoCol]);
            devName = rowArray[devNameCol];
            regNo = Integer.valueOf(rowArray[regNoCol]);
            regType = rowArray[regTypeCol].toLowerCase().contains("holding");
            String dataTypeRaw = rowArray[dataTypeCol].toLowerCase();
            if (dataTypeRaw.contains("float"))
              dataType = DataTypeEnum.Float;
            else if (dataTypeRaw.contains("int"))
              dataType = DataTypeEnum.Int;
            else
              dataType = DataTypeEnum.Coil;

            // TODO - check info? eh maybe...

            // find/create slave device
            BModbusTcpSlaveDevice device = null;
            if (network.isUniqueDeviceAddress(devNo)) {
              device = new BModbusTcpSlaveDevice();
              device.setDeviceAddress(devNo);
              network.add(devName + "_" + devNo, device);
            } else {
              BComponent[] devs = network.getChildComponents();
              for (BComponent dev : devs) {
                if (dev instanceof BModbusTcpSlaveDevice && ((BModbusTcpSlaveDevice) dev).getDeviceAddress() == devNo) {
                  device = (BModbusTcpSlaveDevice) dev;
                  break;
                }
              }
            }
            // create point - TODO - what happens when the point already exists, currently
            // potential chaos
            BControlPoint point;
            String pointName = "Error?";
            BFlexAddress add = new BFlexAddress();
            add.setAddress(String.valueOf(regNo));
            add.setAddressFormat(BAddressFormatEnum.decimal);
            if (regType) { // true = holding (numeric)
              BModbusServerNumericProxyExt proxyExt = new BModbusServerNumericProxyExt();
              point = new BNumericWritable();
              switch (dataType) {
                case Float:
                  pointName = SlotPath.escape("Float" + regNo);
                  proxyExt.setDataType(BDataTypeEnum.floatType);
                  break;
                case Int:
                  pointName = SlotPath.escape("Int" + regNo);
                  proxyExt.setDataType(BDataTypeEnum.integerType);
                  break;
              }
              proxyExt.setDataAddress(add);
              point.setProxyExt(proxyExt);
            } else { // false = coil (boolean)
              BModbusServerBooleanProxyExt proxyExt = new BModbusServerBooleanProxyExt();
              point = new BBooleanWritable();
              proxyExt.setStatusType(BStatusTypeEnum.coil);
              proxyExt.setDataAddress(add);
              point.setProxyExt(proxyExt);
              pointName = SlotPath.escape("Coil" + regNo);
            }
            // set slave points facets to be the same as source point
            if (src != null)
              point.setFacets(src.getFacets());
            // set the register address
            BModbusServerPointDeviceExt points = device.getPoints();
            if (pointName.startsWith("Error"))
              job.log().message("Bad point name - " + rows[i]);
            points.add(pointName, point);
            // add link
            if (point.getLinks().length == 0) { // check if there's an existing link
              if (src != null) { // if the source does not exist we can't create the link
                Type srcSlotType = src.get("out").getType();
                Type destSlotType = point.get("in10").getType();
                BLink link = null;
                if (srcSlotType != destSlotType) {
                  BConverter converter = null;
                  TypeInfo[] adapters = Sys.getRegistry().getAdapters(srcSlotType.getTypeInfo(),
                      destSlotType.getTypeInfo());
                  for (TypeInfo t : adapters) {
                    converter = (BConverter) t.getInstance();
                  }
                  // if (srcSlotType == BStatusEnum.TYPE && destSlotType == BStatusNumeric.TYPE)
                  // converter = new BStatusEnumToStatusNumeric();
                  if (converter != null)
                    link = new BConversionLink(src.getHandleOrd(), "out", "in10", true, converter);
                } else
                  link = new BLink(src.getHandleOrd(), "out", "in10", true);

                if (link != null) {
                  point.add("Link?", link);
                  // job.log().message("Linking: "+src.getSlotPathOrd()+" to
                  // "+point.getSlotPathOrd());
                }
              }
              point.add("subscribeLink", new BLink(point.getHandleOrd(), "out", "in16", true));
            } else
              job.log().message("A link already exists on " + point.getSlotPathOrd());
          } catch (ArrayIndexOutOfBoundsException e) {
            String d = "";
            for (String r : rowArray) {
              d = d + r + ";";
            }
            job.log().failed("Bad line (white space?) - found " + rowArray.length + " col"
                + (rowArray.length != 1 ? "s" : "") + " Data: " + d, e);
          } catch (UnresolvedException e) {
            job.log().failed("Bad Ord: " + rowArray[srcOrdCol], e);
          } catch (UnknownSchemeException e) {
            job.log().failed("Bad Ord: " + rowArray[srcOrdCol], e);
          } catch (Exception e) {
            job.log().failed("Error: " + rows[i], e);
          }
          job.progress((1 * 100) / rows.length); // updates the progress bar in the job
        }

        BComponent[] kids = network.getChildComponents();
        for (BComponent dev : kids) {
          int maxCoil = -1;
          int maxHolding = -1;
          if (dev instanceof BModbusTcpSlaveDevice) {
            BModbusTcpSlaveDevice device = (BModbusTcpSlaveDevice) dev;
            BComponent[] points = device.getPoints().getChildComponents();
            for (BComponent point : points) {
              if (point instanceof BControlPoint) {
                BControlPoint cp = (BControlPoint) point;
                BAbstractProxyExt ext = cp.getProxyExt();
                if (ext instanceof BModbusServerBooleanProxyExt) {
                  BModbusServerBooleanProxyExt boolExt = (BModbusServerBooleanProxyExt) ext;
                  int add = Integer.valueOf(boolExt.getDataAddress().getAddress());
                  if (add >= maxCoil)
                    maxCoil = add;
                } else if (ext instanceof BModbusServerNumericProxyExt) {
                  BModbusServerNumericProxyExt numExt = (BModbusServerNumericProxyExt) ext;
                  int add = Integer.valueOf(numExt.getDataAddress().getAddress());
                  if (add >= maxHolding) {
                    if (numExt.getDataType() == BDataTypeEnum.floatType
                        || numExt.getDataType() == BDataTypeEnum.longType
                        || numExt.getDataType() == BDataTypeEnum.unsignedLong)
                      maxHolding = add + 1;
                    else
                      maxHolding = add;
                  }
                }
              }
            }
            device.getValidInputRegistersRange().clear();
            device.getValidStatusRange().clear();
            device.getValidHoldingRegistersRange().clear();
            if (maxHolding >= 0) {
              BModbusRegisterRangeEntry holdingRange = new BModbusRegisterRangeEntry();
              holdingRange.setCriticalData(false);
              holdingRange.setEnabled(true);
              holdingRange.setStartingAddressOffset(1);
              holdingRange.setSize(maxHolding + 1);
              device.getValidHoldingRegistersRange().addRange(holdingRange);
            }
            device.getValidCoilsRange().clear();
            if (maxCoil >= 0) {
              BModbusRegisterRangeEntry coilRange = new BModbusRegisterRangeEntry();
              coilRange.setCriticalData(false);
              coilRange.setEnabled(true);
              coilRange.setStartingAddressOffset(1);
              coilRange.setSize(maxCoil + 1);
              device.getValidCoilsRange().addRange(coilRange);
            }
          }
        }
      }
      job.log().success("ended task [" + Thread.currentThread().getName() + "]");
      job.progress(100);
    } catch (Exception e) {
      // nothing
      job.log().failed("Failed" + e.getMessage());
    }
  }

  private BRunnableJob job;
}

public enum DataTypeEnum {
  Int, Float, Coil
}