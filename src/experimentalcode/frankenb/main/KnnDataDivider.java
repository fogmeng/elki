/**
 * 
 */
package experimentalcode.frankenb.main;

import java.io.File;
import java.io.IOException;

import de.lmu.ifi.dbs.elki.application.StandAloneApplication;
import de.lmu.ifi.dbs.elki.data.NumberVector;
import de.lmu.ifi.dbs.elki.database.Database;
import de.lmu.ifi.dbs.elki.database.connection.DatabaseConnection;
import de.lmu.ifi.dbs.elki.database.connection.FileBasedDatabaseConnection;
import de.lmu.ifi.dbs.elki.utilities.exceptions.UnableToComplyException;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.OptionID;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameterization.Parameterization;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.IntParameter;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.ObjectParameter;
import experimentalcode.frankenb.log.Log;
import experimentalcode.frankenb.log.LogLevel;
import experimentalcode.frankenb.log.StdOutLogWriter;
import experimentalcode.frankenb.log.TraceLevelLogFormatter;
import experimentalcode.frankenb.model.PackageDescriptor;
import experimentalcode.frankenb.model.PartitionPairing;
import experimentalcode.frankenb.model.datastorage.BufferedDiskBackedDataStorage;
import experimentalcode.frankenb.model.ifaces.IDividerAlgorithm;
import experimentalcode.frankenb.model.ifaces.IPartitionPairingStorage;

/**
 * This application divides a given database into
 * a given numbers of packages to calculate knn
 * on a distributed system like the sun cluster
 * <p />
 * Example usage:
 * <br />
 * <code>-dbc.parser DoubleVectorLabelParser -dbc.in /ELKI/data/synthetic/outlier-scenarios/3-gaussian-2d.csv -app.out D:/tmp/knnparts -packages 3 -partitioner xxx</code>
 * 
 * @author Florian Frankenberger
 */
public class KnnDataDivider extends StandAloneApplication {

  /**
   * No description given.
   * 
   * @author Florian Frankenberger
   */
  private final static class PartitionPairingStorage implements IPartitionPairingStorage {

    private final Database<NumberVector<?, ?>> dataBase;
    private final int packageQuantity;
    private final File outputDir;
    
    private boolean set = false;
    private int partitionPairingsPerPackage = 0;
    private int additionalPairings = 0;
    private PackageDescriptor packageDescriptor;

    private int partitionPairingsCounter = 0;
    private int packageCounter = 0;
    private int totalPartitionPairingsCounter = 0;
    
    private long calculationsTotal = 0;
    
    /**
     * @param dataBase
     */
    private PartitionPairingStorage(Database<NumberVector<?, ?>> dataBase, File outputDir, int packageQuantity) {
      this.dataBase = dataBase;
      this.outputDir = outputDir;
      this.packageQuantity = packageQuantity;
    }

    @Override
    public void setPartitionPairings(int partitionPairings) {
      partitionPairingsPerPackage = partitionPairings / packageQuantity;
      additionalPairings = partitionPairings % packageQuantity;
      set = true;
    }

    @Override
    public void add(PartitionPairing partitionPairing) {
      try {
        if (!set) {
          throw new RuntimeException("You need to set the amount of partition pairings first!");
        }
        
        int totalPairingsForThisPackage = partitionPairingsPerPackage + (packageCounter < additionalPairings ? 1 : 0);
        if (packageDescriptor == null) {
          File targetDirectory = new File(outputDir, String.format("package%05d", packageCounter));
          targetDirectory.mkdirs();
          File packageDescriptorFile = new File(targetDirectory, String.format("package%05d_descriptor.dat", packageCounter));
          int bufferSize = totalPairingsForThisPackage * PackageDescriptor.PAIRING_DATA_SIZE + PackageDescriptor.HEADER_SIZE; 
          packageDescriptor = new PackageDescriptor(packageCounter, this.dataBase.dimensionality(), new BufferedDiskBackedDataStorage(packageDescriptorFile, bufferSize));
          Log.info(String.format("new PackageDescriptor %05d ...", packageCounter));
        }
        
        packageDescriptor.addPartitionPairing(partitionPairing);
        calculationsTotal += partitionPairing.getPartitionOne().getSize() * partitionPairing.getPartitionTwo().getSize();
        partitionPairingsCounter++;
        totalPartitionPairingsCounter++;
        
        if (partitionPairingsCounter % 100 == 0 || partitionPairingsCounter == totalPairingsForThisPackage) {
          Log.info(String.format("%6.2f%% partition pairings persisted (%10d partition pairings of %10d) ...", 
              (partitionPairingsCounter / (float)totalPairingsForThisPackage) * 100, 
              partitionPairingsCounter, 
              totalPairingsForThisPackage
              ));            }
        
        if (partitionPairingsCounter >= totalPairingsForThisPackage) {
          partitionPairingsCounter = 0;
          
          packageDescriptor.close();
          Log.info("Saving package descriptor");
          
          packageDescriptor = null;
          packageCounter++;
        }
      } catch (IOException e) {
        throw new RuntimeException("Could not persist package descriptor", e);
      }
    }
    
    public int getPartitionPairingsCounter() {
      return this.totalPartitionPairingsCounter;
    }
    
    public long getTotalCalculations() {
      return this.calculationsTotal;
    }

    /**
     * @return
     */
    public int getPackageCounter() {
      return packageCounter;
    }
    
  }

  public static final OptionID DIVIDER_ALGORITHM_ID = OptionID.getOrCreateOptionID("algorithm", "A divider algorithm to use");
  /**
   * OptionID for {@link #PACKAGES_PARAM}
   */
  public static final OptionID PACKAGES_ID = OptionID.getOrCreateOptionID("packages", "");
  
  /**
   * Parameter that specifies the number of segments to create (= # of computers)
   * <p>
   * Key: {@code -packages}
   * </p>
   */
  private final IntParameter PACKAGES_PARAM = new IntParameter(PACKAGES_ID, false);
  private int packageQuantity = 0;
  
  
  private final DatabaseConnection<NumberVector<?, ?>> databaseConnection;
  private IDividerAlgorithm algorithm;

  /**
   * @param config
   */
  public KnnDataDivider(Parameterization config) {
    super(config);
    Log.setLogFormatter(new TraceLevelLogFormatter());
    Log.addLogWriter(new StdOutLogWriter());
    Log.setFilter(LogLevel.INFO);
    
    config = config.descend(this);
    if (config.grab(PACKAGES_PARAM)) {
      packageQuantity = PACKAGES_PARAM.getValue();      
    }
        
    final ObjectParameter<IDividerAlgorithm> paramPartitioner = new ObjectParameter<IDividerAlgorithm>(DIVIDER_ALGORITHM_ID, IDividerAlgorithm.class, false);
    if(config.grab(paramPartitioner)) {
      this.algorithm = paramPartitioner.instantiateClass(config);
    }
    
    databaseConnection = new FileBasedDatabaseConnection<NumberVector<?, ?>>(config);
  }

  /* (non-Javadoc)
   * @see de.lmu.ifi.dbs.elki.application.StandAloneApplication#getOutputDescription()
   */
  @Override
  public String getOutputDescription() {
    // TODO Auto-generated method stub
    return null;
  }

  /* (non-Javadoc)
   * @see de.lmu.ifi.dbs.elki.application.AbstractApplication#run()
   */
  @Override
  public void run() throws UnableToComplyException {
    try {
      Log.info("knn data divider started");
      Log.info("reading database ...");
      final Database<NumberVector<?, ?>> dataBase = databaseConnection.getDatabase(null);
      File outputDir = this.getOutput();
      
      if (outputDir.isFile()) 
        throw new UnableToComplyException("You need to specify an output directory not a file!");
      if (!outputDir.exists()) {
        if (!outputDir.mkdirs()) throw new UnableToComplyException("Could not create output directory");
      }
      Log.info(String.format("%d items in db (%d dimensions)", dataBase.size(), dataBase.dimensionality()));
      
      Log.info();
      Log.info("cleaning output directory...");
      clearDirectory(outputDir);
      Log.info();
      
      PartitionPairingStorage storage = new PartitionPairingStorage(dataBase, getOutput(), packageQuantity);
      
      Log.info(String.format("Packages to create: %d", packageQuantity));
      Log.info(String.format("Creating partitions (algorithm used: %s) ...", algorithm.getClass().getSimpleName()));
      
      this.algorithm.divide(dataBase, storage, packageQuantity);
      
      Log.info(String.format("Created %010d packages containing %010d calculations in %010d partition pairings", storage.getPackageCounter(), storage.getTotalCalculations(), storage.getPartitionPairingsCounter()));
      
    } catch (RuntimeException e) {
      throw e;
    } catch (UnableToComplyException e) {
      throw e;
    } catch (Exception e) {
      throw new UnableToComplyException(e);
    }
  }
  
  public static void main(String[] args) {
    StandAloneApplication.runCLIApplication(KnnDataDivider.class, args);
  }

  private static void clearDirectory(File directory) throws UnableToComplyException {
    for (File file : directory.listFiles()) {
      if (file.equals(directory) || file.equals(directory.getParentFile())) continue;
      if (file.isDirectory()) {
        clearDirectory(file);
      }
      if (!file.delete()) throw new UnableToComplyException("Could not delete " + file + ".");
    }
  }

}