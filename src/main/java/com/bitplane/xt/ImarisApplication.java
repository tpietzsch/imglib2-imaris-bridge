package com.bitplane.xt;

import Imaris.Error;
import Imaris.IApplicationPrx;
import net.imagej.Dataset;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;

public interface ImarisApplication
{
	/**
	 * Get the underlying {@code IApplication} ICE proxy.
	 */
	IApplicationPrx getIApplicationPrx();

	/**
	 * Get the object ID of the underlying {@code IApplication} ICE proxy.
	 */
	int getApplicationID();

	/**
	 * Get the current Imaris image as an ImageJ {@code net.imagej.Dataset}.
	 */
	Dataset getIJDataset();

	/**
	 * Get the number of images loaded in the application.
	 */
	int getNumberOfImages();

	/**
	 * Get the Imaris image at {@code imageIndex} as an {@code ImarisDataset}.
	 */
	ImarisDataset< ? > getImage( int imageIndex );

	/**
	 * Get the first Imaris image as an {@code ImarisDataset}.
	 * Equivalent to {@link #getImage getImage(0)}.
	 */
	default ImarisDataset< ? > getDataset()
	{
		return getImage( 0 );
	}

	/**
	 * Set an image to the application. Image index is clamped to be at most the
	 * current number of images in the application.
	 */
	void setImage( int imageIndex, ImarisDataset< ? > dataset );

	/**
	 * Equivalent to {@link #setImage setImage(0,dataset)}.
	 */
	default void setDataset( ImarisDataset< ? > dataset )
	{
		setImage( 0, dataset );
	}

	/**
	 * Create a new {@code ImarisDataset} of the specified {@code type} with the
	 * specified dimensions.
	 * <p>
	 * Sizes {@code s≤0} indicate missing dimensions. For example,
	 * {@code new createDataset(new UnsignedByteType(), 300, 200, 0, 0, 0)} creates a 2D {@code ImarisDataset}
	 * with axes {@code [X,Y]}. The size along missing dimensions (Z, C, T in
	 * this example) is set to {@code s=1} on the Imaris side.
	 *
	 * @param type
	 * 		imglib2 pixel type. Must be one of {@code UnsignedByteType}, {@code UnsignedShortType}, or {@code FloatType}.
	 * @param sx
	 * 		size in X dimension
	 * @param sy
	 * 		size in Y dimension
	 * @param sz
	 * 		size in Z dimension
	 * @param sc
	 * 		size in C (channel) dimension
	 * @param st
	 * 		size in T (time) dimension
	 */
	< T extends NativeType< T > & RealType< T > >
	ImarisDataset< T > createDataset( T type, final int sx, final int sy, final int sz, final int sc, final int st );
}


