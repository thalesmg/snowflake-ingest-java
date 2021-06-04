/*
 * Copyright (c) 2021 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.ingest.streaming.internal;

import com.google.common.annotations.VisibleForTesting;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.xml.bind.DatatypeConverter;
import net.snowflake.client.jdbc.internal.snowflake.common.util.Power10;
import net.snowflake.ingest.utils.ErrorCode;
import net.snowflake.ingest.utils.Logging;
import net.snowflake.ingest.utils.SFException;
import net.snowflake.ingest.utils.Utils;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BaseFixedWidthVector;
import org.apache.arrow.vector.BaseVariableWidthVector;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.util.Text;
import org.apache.arrow.vector.util.TransferPair;

/**
 * The buffer in the Streaming Ingest channel that holds the un-flushed rows, these rows will be
 * converted to Arrow format for faster processing
 */
class ArrowRowBuffer {

  private static final Logging logger = new Logging(ArrowRowBuffer.class);

  // Constants for column fields
  private static final String FIELD_EPOCH_IN_SECONDS = "epoch"; // seconds since epoch
  private static final String FIELD_TIME_ZONE = "timezone"; // time zone index
  private static final String FIELD_FRACTION_IN_NANOSECONDS = "fraction"; // fraction in nanoseconds

  // Column metadata that will send back to server as part of the blob, and will be used by the
  // Arrow reader
  private static final String COLUMN_PHYSICAL_TYPE = "physicalType";
  private static final String COLUMN_LOGICAL_TYPE = "logicalType";
  private static final String COLUMN_SCALE = "scale";
  private static final String COLUMN_PRECISION = "precision";
  private static final String COLUMN_CHAR_LENGTH = "charLength";
  private static final String COLUMN_BYTE_LENGTH = "byteLength";
  @VisibleForTesting static final int DECIMAL_BIT_WIDTH = 128;

  // Snowflake table column logical type
  private static enum ColumnLogicalType {
    ANY,
    BOOLEAN,
    ROWINDEX,
    NULL,
    REAL,
    FIXED,
    TEXT,
    CHAR,
    BINARY,
    DATE,
    TIME,
    TIMESTAMP_LTZ,
    TIMESTAMP_NTZ,
    TIMESTAMP_TZ,
    INTERVAL,
    RAW,
    ARRAY,
    OBJECT,
    VARIANT,
    ROW,
    SEQUENCE,
    FUNCTION,
    USER_DEFINED_TYPE,
  }

  // Snowflake table column physical type
  private static enum ColumnPhysicalType {
    ROWINDEX,
    DOUBLE,
    SB1,
    SB2,
    SB4,
    SB8,
    SB16,
    LOB,
    BINARY,
    ROW,
  }

  // Map the column name to the Arrow vector (buffer)
  @VisibleForTesting final Map<String, FieldVector> vectors;

  // Map the column name to Arrow column field
  private final Map<String, Field> fields;

  // Lock used to protect the buffers from concurrent read/write
  private final Lock flushLock;

  // Current row count
  private volatile long rowCount;

  // Allocator used to allocate the buffers
  private final BufferAllocator allocator;

  // Current buffer size
  private volatile float bufferSize;

  // Current row index in the buffer
  private volatile int curRowIndex;

  // Reference to the Streaming Ingest channel that owns this buffer
  private final SnowflakeStreamingIngestChannelInternal owningChannel;

  /**
   * Given a set of col names to stats, build the right ep info
   *
   * @param rowCount: count of rows in the given arrow buffer
   * @param colStats: map of column name to RowBufferStats
   * @return
   */
  static EpInfo buildEpInfoFromStats(long rowCount, Map<String, RowBufferStats> colStats) {
    EpInfo epInfo = new EpInfo(rowCount, new HashMap<>());
    for (Map.Entry<String, RowBufferStats> colStat : colStats.entrySet()) {
      RowBufferStats stat = colStat.getValue();
      FileColumnProperties dto = new FileColumnProperties(stat);

      String colName = colStat.getKey();
      epInfo.getColumnEps().put(colName, dto);
    }
    return epInfo;
  }

  Map<String, RowBufferStats> statsMap;

  /**
   * Construct a ArrowRowBuffer object
   *
   * @param channel
   */
  ArrowRowBuffer(SnowflakeStreamingIngestChannelInternal channel) {
    this.owningChannel = channel;
    this.allocator = channel.getAllocator();
    this.vectors = new HashMap<>();
    this.fields = new HashMap<>();
    this.flushLock = new ReentrantLock();
    this.rowCount = 0L;
    this.bufferSize = 0F;
    this.curRowIndex = 0;

    // Initialize empty stats
    statsMap = new HashMap<>();
  }

  /**
   * Setup the column fields and vectors using the column metadata from the server
   *
   * @param columns list of column metadata
   */
  void setupSchema(List<ColumnMetadata> columns) {
    for (ColumnMetadata column : columns) {
      Field field = buildField(column);
      FieldVector vector = field.createVector(this.allocator);
      this.fields.put(column.getName(), field);
      this.vectors.put(column.getName(), vector);
      this.statsMap.put(column.getName(), new RowBufferStats());
    }
  }

  /**
   * Close the row buffer and release resources. Note that the caller needs to handle
   * synchronization
   */
  void close() {
    this.vectors.values().forEach(vector -> vector.close());
    this.vectors.clear();
    this.fields.clear();
    this.allocator.close();
  }

  /** Reset the variables after each flush. Note that the caller needs to handle synchronization */
  void reset() {
    this.vectors.values().forEach(vector -> vector.clear());
    this.rowCount = 0L;
    this.bufferSize = 0F;
    this.curRowIndex = 0;
    this.statsMap.replaceAll((key, value) -> new RowBufferStats());
  }

  /**
   * Get the current buffer size
   *
   * @return the current buffer size
   */
  float getSize() {
    return this.bufferSize;
  }

  /**
   * Insert a batch of rows into the row buffer
   *
   * @param rows
   * @param offsetToken offset token of the latest row in the batch
   */
  void insertRows(Iterable<Map<String, Object>> rows, String offsetToken) {
    this.flushLock.lock();
    try {
      for (Map<String, Object> row : rows) {
        convertRowToArrow(row);
        this.rowCount++;
      }
      this.owningChannel.setOffsetToken(offsetToken);
    } catch (Exception e) {
      // TODO SNOW-348857: Return a response instead in case customer wants to skip error rows
      // TODO SNOW-348857: What offset token to return if the latest row failed?
      throw new SFException(e, ErrorCode.INVALID_ROW, e.toString());
    } finally {
      this.flushLock.unlock();
    }
  }

  /**
   * Flush the data in the row buffer by taking the ownership of the old vectors and pass all the
   * required info back to the flush service to build the blob
   *
   * @return A ChannelData object that contains the info needed by the flush service to build a blob
   */
  ChannelData flush() {
    logger.logDebug("Start get data for channel={}", this.owningChannel.getFullyQualifiedName());
    if (this.rowCount > 0) {
      List<FieldVector> oldVectors = new ArrayList<>();
      long rowCount = 0L;
      float bufferSize = 0F;
      long rowSequencer = 0;
      String offsetToken = null;
      Map<String, RowBufferStats> columnEps = null;

      logger.logDebug(
          "Arrow buffer flush about to take lock on channel={}",
          this.owningChannel.getFullyQualifiedName());

      this.flushLock.lock();
      try {
        if (this.rowCount > 0) {
          // Transfer the ownership of the vectors
          for (FieldVector vector : this.vectors.values()) {
            vector.setValueCount(this.curRowIndex);
            TransferPair t = vector.getTransferPair(this.allocator);
            t.transfer();
            oldVectors.add((FieldVector) t.getTo());
          }

          rowCount = this.rowCount;
          bufferSize = this.bufferSize;
          rowSequencer = this.owningChannel.incrementAndGetRowSequencer();
          offsetToken = this.owningChannel.getOffsetToken();
          columnEps = new HashMap(this.statsMap);
          // Reset everything in the buffer once we save all the info
          reset();
        }
      } finally {
        this.flushLock.unlock();
      }

      logger.logDebug(
          "Arrow buffer flush released lock on channel={}, rowCount={}, bufferSize={}",
          this.owningChannel.getFullyQualifiedName(),
          rowCount,
          bufferSize);

      if (!oldVectors.isEmpty()) {
        ChannelData data = new ChannelData();
        data.setVectors(oldVectors);
        data.setRowCount(rowCount);
        data.setBufferSize(bufferSize);
        data.setChannel(this.owningChannel);
        data.setRowSequencer(rowSequencer);
        data.setOffsetToken(offsetToken);
        data.setColumnEps(columnEps);

        return data;
      }
    }
    return null;
  }

  /**
   * Build the column field from the column metadata
   *
   * @param column column metadata
   * @return Column field object
   */
  Field buildField(ColumnMetadata column) {
    ArrowType arrowType;
    FieldType fieldType;
    List<Field> children = null;

    // Put info into the metadata, which will be used by the Arrow reader later
    Map<String, String> metadata = new HashMap<>();
    metadata.put(COLUMN_LOGICAL_TYPE, column.getLogicalType());
    metadata.put(COLUMN_PHYSICAL_TYPE, column.getPhysicalType());

    ColumnPhysicalType physicalType;
    ColumnLogicalType logicalType;
    try {
      physicalType = ColumnPhysicalType.valueOf(column.getPhysicalType());
      logicalType = ColumnLogicalType.valueOf(column.getLogicalType());
    } catch (IllegalArgumentException e) {
      throw new SFException(
          ErrorCode.UNKNOWN_DATA_TYPE, column.getLogicalType(), column.getPhysicalType());
    }

    if (column.getPrecision() != null) {
      metadata.put(COLUMN_PRECISION, column.getPrecision().toString());
    }
    if (column.getScale() != null) {
      metadata.put(COLUMN_SCALE, column.getScale().toString());
    }
    if (column.getByteLength() != null) {
      metadata.put(COLUMN_BYTE_LENGTH, column.getByteLength().toString());
    }
    if (column.getLength() != null) {
      metadata.put(COLUMN_CHAR_LENGTH, column.getLength().toString());
    }

    // Handle differently depends on the column logical and physical types
    switch (logicalType) {
      case FIXED:
        switch (physicalType) {
          case SB1:
            arrowType =
                column.getScale() == 0
                    ? Types.MinorType.TINYINT.getType()
                    : new ArrowType.Decimal(
                        column.getPrecision(), column.getScale(), DECIMAL_BIT_WIDTH);
            break;
          case SB2:
            arrowType =
                column.getScale() == 0
                    ? Types.MinorType.SMALLINT.getType()
                    : new ArrowType.Decimal(
                        column.getPrecision(), column.getScale(), DECIMAL_BIT_WIDTH);
            break;
          case SB4:
            arrowType =
                column.getScale() == 0
                    ? Types.MinorType.INT.getType()
                    : new ArrowType.Decimal(
                        column.getPrecision(), column.getScale(), DECIMAL_BIT_WIDTH);
            break;
          case SB8:
            arrowType =
                column.getScale() == 0
                    ? Types.MinorType.BIGINT.getType()
                    : new ArrowType.Decimal(
                        column.getPrecision(), column.getScale(), DECIMAL_BIT_WIDTH);
            break;
          case SB16:
            arrowType =
                new ArrowType.Decimal(column.getPrecision(), column.getScale(), DECIMAL_BIT_WIDTH);
            break;
          default:
            throw new SFException(
                ErrorCode.UNKNOWN_DATA_TYPE, column.getLogicalType(), column.getPhysicalType());
        }
        break;
      case ANY:
      case ARRAY:
      case CHAR:
      case TEXT:
      case OBJECT:
      case VARIANT:
        arrowType = Types.MinorType.VARCHAR.getType();
        break;
      case TIMESTAMP_LTZ:
      case TIMESTAMP_NTZ:
        switch (physicalType) {
          case SB8:
            arrowType = Types.MinorType.BIGINT.getType();
            break;
          case SB16:
            arrowType = Types.MinorType.STRUCT.getType();
            FieldType fieldTypeEpoch =
                new FieldType(true, Types.MinorType.BIGINT.getType(), null, metadata);
            FieldType fieldTypeFraction =
                new FieldType(true, Types.MinorType.INT.getType(), null, metadata);
            Field fieldEpoch = new Field(FIELD_EPOCH_IN_SECONDS, fieldTypeEpoch, null);
            Field fieldFraction = new Field(FIELD_FRACTION_IN_NANOSECONDS, fieldTypeFraction, null);
            children = new LinkedList<>();
            children.add(fieldEpoch);
            children.add(fieldFraction);
            break;
          default:
            throw new SFException(
                ErrorCode.UNKNOWN_DATA_TYPE, column.getLogicalType(), column.getPhysicalType());
        }
        break;

        // TODO: not currently supported in convertRowToArrow
        //      case TIMESTAMP_TZ:
        //        switch (physicalType) {
        //          case SB8:
        //            arrowType = Types.MinorType.STRUCT.getType();
        //            FieldType fieldTypeEpoch =
        //                new FieldType(true, Types.MinorType.BIGINT.getType(), null, metadata);
        //            FieldType fieldTypeTimezone =
        //                new FieldType(true, Types.MinorType.INT.getType(), null, metadata);
        //            Field fieldEpoch = new Field(Constants.FIELD_NAME_EPOCH, fieldTypeEpoch,
        // null);
        //            Field fieldTimezone = new Field(Constants.FIELD_NAME_FRACTION,
        // fieldTypeTimezone, null);
        //            children = new LinkedList<>();
        //            children.add(fieldEpoch);
        //            children.add(fieldTimezone);
        //            break;
        //          case SB16:
        //            arrowType = Types.MinorType.STRUCT.getType();
        //            fieldTypeEpoch = new FieldType(true, Types.MinorType.BIGINT.getType(), null,
        // metadata);
        //            FieldType fieldTypeFraction =
        //                new FieldType(true, Types.MinorType.INT.getType(), null, metadata);
        //            fieldTypeTimezone = new FieldType(true, Types.MinorType.INT.getType(), null,
        // metadata);
        //            fieldEpoch = new Field(Constants.FIELD_NAME_EPOCH, fieldTypeEpoch, null);
        //            Field fieldFraction = new Field(Constants.FIELD_NAME_FRACTION,
        // fieldTypeFraction, null);
        //            fieldTimezone =
        //                new Field(Constants.FIELD_NAME_TIME_ZONE_INDEX, fieldTypeTimezone, null);
        //
        //            children = new LinkedList<>();
        //            children.add(fieldEpoch);
        //            children.add(fieldFraction);
        //            children.add(fieldTimezone);
        //            break;
        //          default:
        //            throw new SFException(
        //                ErrorCode.UNKNOWN_DATA_TYPE,
        //                "Unknown physical type for TIMESTAMP_TZ: " + physicalType);
        //        }
        //        break;
      case DATE:
        arrowType = Types.MinorType.DATEDAY.getType();
        break;
      case TIME:
        switch (physicalType) {
          case SB4:
            arrowType = Types.MinorType.INT.getType();
            break;
          case SB8:
            arrowType = Types.MinorType.BIGINT.getType();
            break;
          default:
            throw new SFException(
                ErrorCode.UNKNOWN_DATA_TYPE, column.getLogicalType(), column.getPhysicalType());
        }
        break;
      case BOOLEAN:
        arrowType = Types.MinorType.BIT.getType();
        break;
      case BINARY:
        arrowType = Types.MinorType.VARBINARY.getType();
        break;
      case REAL:
        arrowType = Types.MinorType.FLOAT8.getType();
        break;
      default:
        throw new SFException(
            ErrorCode.UNKNOWN_DATA_TYPE, column.getLogicalType(), column.getPhysicalType());
    }

    // Create the corresponding column field base on the column data type
    fieldType = new FieldType(column.getNullable(), arrowType, null, metadata);
    return new Field(column.getName(), fieldType, children);
  }

  /**
   * Convert the input row to the correct Arrow format
   *
   * @param row input row
   */
  // TODO: need to verify each row with the table schema
  private void convertRowToArrow(Map<String, Object> row) {
    for (Map.Entry<String, Object> entry : row.entrySet()) {
      this.bufferSize += 0.125; // 1/8 for null value bitmap
      String columnName = entry.getKey();
      Utils.assertStringNotNullOrEmpty("invalid column name", columnName);
      columnName =
          (columnName.charAt(0) == '"' && columnName.charAt(columnName.length() - 1) == '"')
              ? columnName.substring(1, columnName.length() - 1)
              : columnName.toUpperCase();
      Object value = entry.getValue();
      Field field = this.fields.get(columnName);
      Utils.assertNotNull("Arrow column field", field);
      FieldVector vector = this.vectors.get(columnName);
      Utils.assertNotNull("Arrow column vector", vector);
      RowBufferStats stats = statsMap.get(columnName);
      Utils.assertNotNull("Arrow column stats", stats);
      ColumnLogicalType logicalType =
          ColumnLogicalType.valueOf(field.getMetadata().get(COLUMN_LOGICAL_TYPE));
      ColumnPhysicalType physicalType =
          ColumnPhysicalType.valueOf(field.getMetadata().get(COLUMN_PHYSICAL_TYPE));

      if (value == null) {
        if (BaseFixedWidthVector.class.isAssignableFrom(vector.getClass())) {
          ((BaseFixedWidthVector) vector).setNull(this.curRowIndex);
        } else if (BaseVariableWidthVector.class.isAssignableFrom(vector.getClass())) {
          ((BaseVariableWidthVector) vector).setNull(this.curRowIndex);
        } else if (vector instanceof StructVector) {
          ((StructVector) vector).setNull(this.curRowIndex);
          ((StructVector) vector)
              .getChildrenFromFields()
              .forEach(
                  child -> {
                    ((BaseFixedWidthVector) child).setNull(this.curRowIndex);
                  });
        } else {
          throw new SFException(ErrorCode.INTERNAL_ERROR, "Unexpected FieldType");
        }
        stats.incCurrentNullCount();
      } else {
        switch (logicalType) {
          case FIXED:
            switch (physicalType) {
              case SB1:
                ((TinyIntVector) vector).setSafe(this.curRowIndex, (byte) value);
                stats.addIntValue(BigInteger.valueOf((byte) value));
                this.bufferSize += 1;
                break;
              case SB2:
                ((SmallIntVector) vector).setSafe(this.curRowIndex, (short) value);
                stats.addIntValue(BigInteger.valueOf((short) value));
                this.bufferSize += 2;
                break;
              case SB4:
                ((IntVector) vector).setSafe(this.curRowIndex, (int) value);
                stats.addIntValue(BigInteger.valueOf((int) value));
                this.bufferSize += 4;
                break;
              case SB8:
                ((BigIntVector) vector).setSafe(this.curRowIndex, (long) value);
                stats.addIntValue(BigInteger.valueOf((long) value));
                this.bufferSize += 8;
                break;
              case SB16:
                ((DecimalVector) vector)
                    .setSafe(this.curRowIndex, new BigDecimal(value.toString()));
                BigDecimal decimalVal = new BigDecimal(value.toString());
                stats.addIntValue(decimalVal.toBigInteger());
                this.bufferSize += 16;
                break;
              default:
                throw new SFException(ErrorCode.UNKNOWN_DATA_TYPE, logicalType, physicalType);
            }
            break;
          case ANY:
          case ARRAY:
          case CHAR:
          case TEXT:
          case OBJECT:
          case VARIANT:
            String str = value.toString();
            Text text = new Text(str);
            ((VarCharVector) vector).setSafe(this.curRowIndex, text);
            int len = text.getBytes().length;
            stats.setCurrentMaxLength(len); // TODO confirm max length for strings
            stats.addStrValue(str);
            this.bufferSize += text.getBytes().length;
            break;
          case TIMESTAMP_LTZ:
          case TIMESTAMP_NTZ:
            switch (physicalType) {
              case SB8:
                {
                  BigIntVector bigIntVector = (BigIntVector) vector;
                  int scale = Integer.parseInt(field.getMetadata().get(COLUMN_SCALE));
                  String valueString = getStringValue(value);
                  BigInteger timeInScale = getTimeInScale(valueString, scale);
                  bigIntVector.setSafe(curRowIndex, timeInScale.longValue());
                  stats.addIntValue(timeInScale);
                  this.bufferSize += 8;
                  break;
                }
              case SB16:
                {
                  StructVector structVector = (StructVector) vector;
                  BigIntVector epochVector =
                      (BigIntVector) structVector.getChild(FIELD_EPOCH_IN_SECONDS);
                  IntVector fractionVector =
                      (IntVector) structVector.getChild(FIELD_FRACTION_IN_NANOSECONDS);
                  this.bufferSize += 0.25; // for children vector's null value
                  structVector.setIndexDefined(curRowIndex);
                  int scale = Integer.parseInt(field.getMetadata().get(COLUMN_SCALE));
                  String valueString = getStringValue(value);

                  String[] items = valueString.split("\\.");
                  long epoch = Long.parseLong(items[0]);
                  int l = items.length > 1 ? items[1].length() : 0;
                  // Fraction is in nanoseconds, but Snowflake will error if the fraction gives
                  // accuracy greater than the scale
                  int fraction =
                      l == 0
                          ? 0
                          : Integer.parseInt(items[1]) * (l < 9 ? Power10.intTable[9 - l] : 1);
                  if (fraction % Power10.intTable[9 - scale] != 0) {
                    throw new SFException(
                        ErrorCode.INVALID_ROW, "Row specifies accuracy greater than column scale");
                  }
                  epochVector.setSafe(curRowIndex, epoch);
                  fractionVector.setSafe(curRowIndex, fraction);
                  this.bufferSize += 12;
                  stats.addIntValue(getTimeInScale(valueString, scale));
                  break;
                }
              default:
                throw new SFException(ErrorCode.UNKNOWN_DATA_TYPE, logicalType, physicalType);
            }
            break;
          case DATE:
            DateDayVector dateDayVector = (DateDayVector) vector;
            // Expect days past the epoch
            dateDayVector.setSafe(curRowIndex, Integer.parseInt((String) value));
            stats.addIntValue(new BigInteger((String) value));
            this.bufferSize += 4;
            break;
          case TIME:
            switch (physicalType) {
              case SB4:
                {
                  int scale = Integer.parseInt(field.getMetadata().get(COLUMN_SCALE));
                  BigInteger timeInScale = getTimeInScale(getStringValue(value), scale);
                  stats.addIntValue(timeInScale);
                  ((IntVector) vector).setSafe(curRowIndex, timeInScale.intValue());
                  stats.addIntValue(timeInScale);
                  this.bufferSize += 4;
                  break;
                }
              case SB8:
                {
                  int scale = Integer.parseInt(field.getMetadata().get(COLUMN_SCALE));
                  BigInteger timeInScale = getTimeInScale(getStringValue(value), scale);
                  ((BigIntVector) vector).setSafe(curRowIndex, timeInScale.longValue());
                  stats.addIntValue(timeInScale);
                  this.bufferSize += 8;
                  break;
                }
              default:
                throw new SFException(ErrorCode.UNKNOWN_DATA_TYPE, logicalType, physicalType);
            }
            break;
          case BOOLEAN:
            int intValue;
            if (value instanceof Boolean) {
              intValue = (boolean) value ? 1 : 0;
            } else if (value instanceof Number) {
              intValue = ((Number) value).doubleValue() > 0 ? 1 : 0;
            } else {
              intValue = this.convertStringToBoolean((String) value) ? 1 : 0;
            }
            ((BitVector) vector).setSafe(curRowIndex, intValue);
            this.bufferSize += 0.125;
            stats.addIntValue(BigInteger.valueOf(intValue));
            break;
          case BINARY:
            byte[] bytes;
            if (value instanceof byte[]) {
              bytes = (byte[]) value;
            } else {
              bytes = DatatypeConverter.parseHexBinary((String) value);
            }
            ((VarBinaryVector) vector).setSafe(curRowIndex, bytes);
            stats.setCurrentMaxLength(bytes.length);
            this.bufferSize += bytes.length;
            break;
          case REAL:
            double doubleValue;
            if (value instanceof Float || value instanceof Double) {
              doubleValue = (double) value;
            } else if (value instanceof BigDecimal) {
              doubleValue = ((BigDecimal) value).doubleValue();
            } else {
              doubleValue = Double.parseDouble((String) value);
            }
            ((Float8Vector) vector).setSafe(curRowIndex, doubleValue);
            stats.addRealValue(doubleValue);
            this.bufferSize += 8;
            break;
          default:
            throw new SFException(ErrorCode.UNKNOWN_DATA_TYPE, logicalType, physicalType);
        }
      }
    }
    this.curRowIndex++;
  }

  boolean convertStringToBoolean(String value) {
    return "1".equalsIgnoreCase(value)
        || "yes".equalsIgnoreCase(value)
        || "y".equalsIgnoreCase(value)
        || "t".equalsIgnoreCase(value)
        || "true".equalsIgnoreCase(value)
        || "on".equalsIgnoreCase(value);
  }

  BigInteger getTimeInScale(String value, int scale) {
    BigDecimal decVal = new BigDecimal(value);
    BigDecimal epochScale = decVal.multiply(BigDecimal.valueOf(Power10.intTable[scale]));
    return epochScale.toBigInteger();
  }

  String getStringValue(Object value) {
    String stringValue;

    if (value instanceof String) {
      stringValue = (String) value;
    } else if (value instanceof BigDecimal) {
      stringValue = ((BigDecimal) value).toPlainString();
    } else {
      stringValue = value.toString();
    }

    return stringValue;
  }
}
