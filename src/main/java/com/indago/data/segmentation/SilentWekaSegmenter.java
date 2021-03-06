/**
 *
 */
package com.indago.data.segmentation;

import java.util.ArrayList;
import java.util.List;

import com.indago.tr2d.plugins.seg.IndagoWekaSegmentationPlugin;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import indago.ui.progress.ProgressListener;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealDoubleConverter;
import net.imglib2.img.ImagePlusAdapter;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.Views;
import trainableSegmentation.WekaSegmentation;

/**
 * @author jug
 */
public class SilentWekaSegmenter< T extends NumericType< T > > {

	/** reference to the segmentation backend */
	WekaSegmentation wekaSegmentation = null;

	public SilentWekaSegmenter( final String directory, final String filename ) {
		// instantiate segmentation backend
		wekaSegmentation = new WekaSegmentation( IJ.createImage( "unused dummy", 10, 5, 0, 16 ) );
		loadClassifier( directory, filename );
	}

	public boolean loadClassifier( final String directory, final String filename ) {
		// Try to load Weka model (classifier and train header)
		if ( false == wekaSegmentation.loadClassifier( directory + filename ) ) {
			IJ.error( "Error when loading Weka classifier from file: " + directory + filename );
			IndagoWekaSegmentationPlugin.log.error( "Classifier could not be loaded from '" + directory + filename + "'." );
			return false;
		}

		IndagoWekaSegmentationPlugin.log.info(
				"Read header from " + directory + filename + " (number of attributes = " + wekaSegmentation.getTrainHeader().numAttributes() + ")" );

		if ( wekaSegmentation.getTrainHeader().numAttributes() < 1 ) {
			IndagoWekaSegmentationPlugin.log.error( "No attributes were found on the model header loaded from " + directory + filename );
			return false;
		}

		return true;
	}

	public RandomAccessibleInterval< DoubleType > classifyPixels(
			final RandomAccessibleInterval< T > img,
			final boolean probabilityMaps,
			final List< ProgressListener > progressListeners ) {
		final List< RandomAccessibleInterval< T >> rais = new ArrayList< RandomAccessibleInterval< T >>();
		rais.add( img );
		final List< RandomAccessibleInterval< DoubleType > > result = classifyPixels( rais, probabilityMaps, progressListeners );
		return result.get( 0 );
	}

	public RandomAccessibleInterval< DoubleType > classifyPixelSlicesInThreads(
			final RandomAccessibleInterval< T > img,
			final int sliceDimension,
			final boolean probabilityMaps,
			final List< ProgressListener > progressListeners ) {

		final List< RandomAccessibleInterval< T > > rais = new ArrayList< RandomAccessibleInterval< T > >();
		for ( long i = img.min( sliceDimension ); i <= img.max( sliceDimension ); i++ ) {
			rais.add( Views.hyperSlice( img, sliceDimension, i ) );
		}
		final List< RandomAccessibleInterval< DoubleType > > slices3d = classifyPixels( rais, probabilityMaps, progressListeners );
		final List< RandomAccessibleInterval< DoubleType > > slices = new ArrayList< >();
		for ( int i = 0; i < slices3d.size(); i++ ) {
			slices.add( Views.hyperSlice( slices3d.get( i ), 2, 0 ) );
			slices.add( Views.hyperSlice( slices3d.get( i ), 2, 1 ) );
		}
		return Views.stack( slices );
	}

	public List< RandomAccessibleInterval< DoubleType > > classifyPixels(
			final List< RandomAccessibleInterval< T > > raiList,
			final boolean probabilityMaps,
			final List< ProgressListener > progressListeners ) {

		final List< RandomAccessibleInterval< DoubleType >> results = new ArrayList< RandomAccessibleInterval< DoubleType >>();
		for ( int i = 0; i < raiList.size(); ++i )
			results.add( null );

		final int numProcessors = Prefs.getThreads();
		final int numThreads = Math.min( raiList.size(), numProcessors );
		final int numFurtherThreads = ( int ) Math.ceil( ( double ) ( numProcessors - numThreads ) / raiList.size() ) + 1;

		final String message = "Processing " + raiList.size() + " image files in " + numThreads + " thread(s)....";
		IndagoWekaSegmentationPlugin.log.info( message );
		for ( final ProgressListener pl : progressListeners ) {
			pl.resetProgress( message, raiList.size() );
		}

		final Thread[] threads = new Thread[ numThreads ];

		class ImageProcessingThread extends Thread {

			final int numThread;
			final int numThreads;
			final List< RandomAccessibleInterval< T >> raiList;
			final List< RandomAccessibleInterval< DoubleType >> raiListOutputs;

			public ImageProcessingThread( final int numThread, final int numThreads, final List< RandomAccessibleInterval< T >> raiList, final List< RandomAccessibleInterval< DoubleType >> raiListOutputs ) {
				this.numThread = numThread;
				this.numThreads = numThreads;
				this.raiList = raiList;
				this.raiListOutputs = raiListOutputs;
			}

			@Override
			public void run() {

				for ( int i = numThread; i < raiList.size(); i += numThreads ) {

					final ImagePlus forWekaImagePlus = ImageJFunctions.wrap( raiList.get( i ), "Img_num_" + i ).duplicate();

					final ImagePlus segmentation = wekaSegmentation.applyClassifier( forWekaImagePlus, numFurtherThreads, probabilityMaps );

					if ( null != segmentation ) {
						final RandomAccessibleInterval< ? > temp =
								ImagePlusAdapter.wrapReal( segmentation );
						final RandomAccessibleInterval< DoubleType > rai =
								Converters.convert(
										temp,
										new RealDoubleConverter(),
										new DoubleType() );
						raiListOutputs.set( i, rai );
					} else {
						IndagoWekaSegmentationPlugin.log.warn( "One of the input images could not be classified!!!" );
					}

					IndagoWekaSegmentationPlugin.log.info( "Processed image " + ( i + 1 ) + " in thread " + ( numThread + 1 ) );
					for ( final ProgressListener pl : progressListeners ) {
						pl.hasProgressed();
					}

					segmentation.close();
					forWekaImagePlus.close();
				}
			}
		}

		// start threads
		for ( int i = 0; i < numThreads; i++ ) {
			threads[ i ] = new ImageProcessingThread( i, numThreads, raiList, results );
			threads[ i ].start();
		}

		// wait for all threads to terminate
		for ( final Thread thread : threads ) {
			try {
				thread.join();
			} catch ( final InterruptedException e ) {}
		}

		return results;
	}
}
