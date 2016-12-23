package io.indexr.hive;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.FileSinkOperator;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory;
import org.apache.hadoop.io.ArrayWritable;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordWriter;
import org.apache.hadoop.mapred.Reporter;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import io.indexr.segment.ColumnSchema;
import io.indexr.segment.ColumnType;
import io.indexr.segment.SegmentSchema;
import io.indexr.segment.helper.SimpleRow;
import io.indexr.segment.pack.DPSegment;
import io.indexr.segment.pack.OpenOption;

public class IndexRRecordWriter implements FileSinkOperator.RecordWriter, RecordWriter<Void, ArrayWritable> {
    private static final Log logger = LogFactory.getLog(IndexRRecordWriter.class);

    private DPSegment segment;
    private byte[] columnTypes;
    private SimpleRow.Builder rowBuilder;
    private FileSystem fileSystem;
    private java.nio.file.Path localSegmentPath;
    private String segmentName;

    private Path tableLocation;
    private Path segmentOutPath;

    public IndexRRecordWriter(JobConf jobConf,
                              List<String> columnNames,
                              List<TypeInfo> columnTypes,
                              Path finalOutPath,
                              Path tableLocation,
                              boolean compress) throws IOException {
        // Hive may ask to create a file located on local file system.
        // We have to get the real file system by path's schema.
        this.fileSystem = FileSystem.get(finalOutPath.toUri(), FileSystem.get(jobConf).getConf());

        this.localSegmentPath = Files.createTempDirectory("_segment_gen_");
        this.tableLocation = tableLocation;
        this.segmentOutPath = finalOutPath;

        SegmentSchema schema = convertToIndexRSchema(columnNames, columnTypes);
        this.columnTypes = new byte[schema.columns.size()];
        int i = 0;
        for (ColumnSchema sc : schema.columns) {
            this.columnTypes[i] = sc.dataType;
            i++;
        }
        this.rowBuilder = SimpleRow.Builder.createByColumnSchemas(schema.columns);

        segment = DPSegment.open(localSegmentPath.toString(), segmentName, schema, OpenOption.Overwrite);
        segment.setCompress(compress).update();
    }

    private SegmentSchema convertToIndexRSchema(List<String> columnNames, List<TypeInfo> columnTypes) throws IOException {
        List<ColumnSchema> schemas = new ArrayList<ColumnSchema>();
        for (int i = 0; i < columnNames.size(); i++) {
            String currentColumn = columnNames.get(i);
            TypeInfo currentType = columnTypes.get(i);
            Byte convertedType = null;

            if (currentType.equals(TypeInfoFactory.intTypeInfo)) {
                convertedType = ColumnType.INT;
            } else if (currentType.equals(TypeInfoFactory.longTypeInfo)) {
                convertedType = ColumnType.LONG;
            } else if (currentType.equals(TypeInfoFactory.floatTypeInfo)) {
                convertedType = ColumnType.FLOAT;
            } else if (currentType.equals(TypeInfoFactory.doubleTypeInfo)) {
                convertedType = ColumnType.DOUBLE;
            } else if (currentType.equals(TypeInfoFactory.stringTypeInfo)) {
                convertedType = ColumnType.STRING;
            } else {
                throw new IOException("can't recognize this type [" + currentType.getTypeName() + "]");
            }

            schemas.add(new ColumnSchema(currentColumn, convertedType));
        }
        return new SegmentSchema(schemas);
    }

    @Override
    public void write(Writable w) throws IOException {
        ArrayWritable datas = (ArrayWritable) w;
        for (int colId = 0; colId < columnTypes.length; colId++) {
            byte type = columnTypes[colId];
            Writable currentValue = datas.get()[colId];
            switch (type) {
                case ColumnType.INT:
                    if (currentValue == null) {
                        rowBuilder.appendInt(0);
                    } else {
                        rowBuilder.appendInt(((IntWritable) currentValue).get());
                    }
                    break;
                case ColumnType.LONG:
                    if (currentValue == null) {
                        rowBuilder.appendLong(0L);
                    } else {
                        rowBuilder.appendLong(((LongWritable) currentValue).get());
                    }
                    break;
                case ColumnType.FLOAT:
                    if (currentValue == null) {
                        rowBuilder.appendFloat(0f);
                    } else {
                        rowBuilder.appendFloat(((FloatWritable) currentValue).get());
                    }
                    break;
                case ColumnType.DOUBLE:
                    if (currentValue == null) {
                        rowBuilder.appendDouble(0d);
                    } else {
                        rowBuilder.appendDouble(((DoubleWritable) currentValue).get());
                    }
                    break;
                case ColumnType.STRING:
                    if (currentValue == null) {
                        rowBuilder.appendString("");
                    } else {
                        rowBuilder.appendString(((Text) currentValue).toString());
                    }
                    break;
                default:
                    throw new IOException("can't recognize this type [" + type + "]");
            }
        }
        segment.add(rowBuilder.buildAndReset());
    }

    @Override
    public void close(boolean abort) throws IOException {
        try {
            segment.seal();
            rowBuilder = null;
            if (!abort) {
                SegmentHelper.uploadSegment(segment, fileSystem, segmentOutPath, tableLocation);
            }
        } finally {
            // Remove temporary dir.
            FileUtils.deleteDirectory(localSegmentPath.toFile());
        }
    }

    @Override
    public void write(Void key, ArrayWritable value) throws IOException {}

    @Override
    public void close(Reporter reporter) throws IOException {
        close(true);
    }

}