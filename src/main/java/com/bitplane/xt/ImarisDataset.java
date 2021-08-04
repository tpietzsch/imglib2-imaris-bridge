package com.bitplane.xt;

import Imaris.Error;
import Imaris.IDataSetPrx;
import bdv.util.AxisOrder;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import com.bitplane.xt.util.ColorTableUtils;
import com.bitplane.xt.util.ModifiableVoxelDimensions;
import java.util.ArrayList;
import java.util.List;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.CalibratedAxis;
import net.imagej.axis.DefaultLinearAxis;
import net.imglib2.Volatile;
import net.imglib2.display.ColorTable8;
import net.imglib2.img.Img;
import net.imglib2.img.basictypeaccess.volatiles.VolatileArrayDataAccess;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;

/**
 * Wraps Imaris {@code IDataSetPrx} into {@code CachedCellImg}s that are lazy-loaded.
 * <p>
 * The data is provided as
 * <ul>
 *     <li>an {@code Img} ({@link #getImage}),</li>
 *     <li>an {@code ImgPlus} with the correct metadata ({@link #getImgPlus}), and</li>
 *     <li>a list of {@code SourceAndConverter}, one for each channel, for display in BDV ({@link #getSources}).</li>
 * </ul>
 * All these are views on the same data, backed by a common cache.
 * The BDV sources are multi-resolution and have volatile versions.
 *
 * @param <T>
 * 		imglib2 pixel type
 */
public class ImarisDataset< T extends NativeType< T > & RealType< T > >
{
	private final IDataSetPrx dataset;

	/**
	 * physical calibration: size of voxel in X,Y,Z
	 */
	private final ModifiableVoxelDimensions voxelDimensions;

	/**
	 * Non-volatile and volatile images for each resolution, backed by a joint cache which loads blocks from Imaris.
	 */
	private final CachedImagePyramid< T, ?, ? > imagePyramid;

	/**
	 * ImgPlus wrapping full resolution image.
	 * Metadata and color tables are set up according to Imaris (at the time of construction of this {@code ImarisDataset}).
	 */
	private final ImgPlus< T > imp;

	/**
	 * List of sources, one for each channel of the dataset.
	 * The sources provide nested volatile versions.
	 */
	private final List< SourceAndConverter< T > > sources;

	// open existing
	public < V extends Volatile< T > & NativeType< V > & RealType< V >, A extends VolatileArrayDataAccess< A > >
	ImarisDataset( final IDataSetPrx dataset ) throws Error
	{
		this( dataset, ImarisDatasetOptions.options() );
	}

	// open existing
	public < V extends Volatile< T > & NativeType< V > & RealType< V >, A extends VolatileArrayDataAccess< A > >
	ImarisDataset( final IDataSetPrx dataset, final ImarisDatasetOptions options ) throws Error
	{
		this( dataset, new DatasetDimensions( dataset ), false, false, options );
	}

	public < V extends Volatile< T > & NativeType< V > & RealType< V >, A extends VolatileArrayDataAccess< A > >
	ImarisDataset(
			final IDataSetPrx dataset,
			final DatasetDimensions datasetDimensions,
			final boolean writable,
			final boolean isEmptyDataset,
			final ImarisDatasetOptions options ) throws Error
	{

		this.dataset = dataset;

		// --------------------------------------------------------------------
		// Get calibration from dataset.

		voxelDimensions = new ModifiableVoxelDimensions( ImarisUtils.getVoxelDimensions( dataset ) );


		// --------------------------------------------------------------------
		// Analyze pyramid sizes and derive imglib2 dimensions.

		final int[][] pyramidSizes = dataset.GetPyramidSizes();
		final int[][] pyramidBlockSizes = dataset.GetPyramidBlockSizes();
		final int numResolutions = pyramidSizes.length;
		final AxisOrder axisOrder = datasetDimensions.getAxisOrder();
		final int numDimensions = axisOrder.numDimensions();
		final int[] mapDimensions = datasetDimensions.getMapDimensions();

		final long[][] dimensions = new long[ numResolutions ][ numDimensions ];
		final int[][] cellDimensions = new int[ numResolutions ][ numDimensions ];
		for ( int l = 0; l < numResolutions; ++l )
		{
			for ( int i = 0; i < 5; ++i )
			{
				final int d = mapDimensions[ i ];
				if ( d >= 0 )
				{
					dimensions[ l ][ d ] = pyramidSizes[ l ][ i ];
					cellDimensions[ l ][ d ] =  i < 3
							? pyramidBlockSizes[ l ][ i ]
							: 1;
				}
			}
		}

		// handle optional cellDimensions override (for full resolution only)
		if ( options.values.cellDimensions() != null )
		{
			final int[] optionalCellDimensions = options.values.cellDimensions();
			final int max = optionalCellDimensions.length - 1;
			for ( int i = 0; i < 5; ++i )
			{
				final int d = mapDimensions[ i ];
				if ( d >= 0 )
					cellDimensions[ 0 ][ d ] =  optionalCellDimensions[ Math.min( d, max ) ];
			}
		}


		// --------------------------------------------------------------------
		// Create cached images.

		final T type = ImarisUtils.imglibTypeFor( dataset.GetType() );
		final SharedQueue queue = new SharedQueue( 16, numResolutions );
		final CachedImagePyramid< T, V, A > imagePyramid = new CachedImagePyramid<>(
				type, axisOrder, dataset,
				dimensions, cellDimensions, mapDimensions,
				queue,
				writable,
				isEmptyDataset,
				options
		);
		this.imagePyramid = imagePyramid;


		// --------------------------------------------------------------------
		// Create ImgPlus with metadata and color tables.

		final Img< T > img = getImage();
		imp = new ImgPlus<>( img );
		imp.setName( getName() );
		updateImpAxes();
		updateImpColorTables();


		// --------------------------------------------------------------------
		// Instantiate multi-resolution sources.


		// --------------------------------------------------------------------
		// TODO: get rid of these:
		final int sc = dataset.GetSizeC();
		final double minX = dataset.GetExtendMinX();
		final double minY = dataset.GetExtendMinY();
		final double minZ = dataset.GetExtendMinZ();
		// --------------------------------------------------------------------

		sources = new ArrayList<>();

		final double[][] mipmapScales = new double[ numResolutions ][ 3 ];
		mipmapScales[ 0 ][ 0 ] = 1;
		mipmapScales[ 0 ][ 1 ] = 1;
		mipmapScales[ 0 ][ 2 ] = 1;
		for ( int level = 1; level < numResolutions; ++level )
		{
			for ( int d = 0; d < 3; ++d )
			{
				final boolean half = pyramidSizes[ level - 1 ][ d ] / 2 == pyramidSizes[ level ][ d ];
				final double s = half ? 2 : 1;
				mipmapScales[ level ][ d ] = s * mipmapScales[ level - 1 ][ d ];
			}
		}

		final List< ImagePyramid< T, V > > channelPyramids = imagePyramid.splitIntoSourceStacks();
		final V volatileType = imagePyramid.getVolatileType();
		final boolean hasTimepoints = axisOrder.hasTimepoints();
		for ( int c = 0; c < sc; ++c )
		{
			final String name = String.format( "%s - %s", getName(), dataset.GetChannelName( c ) );
			final ImagePyramid< T, V > channelPyramid = channelPyramids.get( c );
			final Source< T > source = hasTimepoints
					? new ImarisSource4D<>( voxelDimensions, minX, minY, minZ, type, channelPyramid.getImgs(), mipmapScales, name )
					: new ImarisSource3D<>( voxelDimensions, minX, minY, minZ, type, channelPyramid.getImgs(), mipmapScales, name );
			final Source< V > volatileSource = hasTimepoints
					? new ImarisSource4D<>( voxelDimensions, minX, minY, minZ, volatileType, channelPyramid.getVolatileImgs(), mipmapScales, name )
					: new ImarisSource3D<>( voxelDimensions, minX, minY, minZ, volatileType, channelPyramid.getVolatileImgs(), mipmapScales, name );
			final SourceAndConverter< V > vsoc = new SourceAndConverter<>( volatileSource, ColorTableUtils.createChannelConverterToARGB( volatileType, dataset, c ) );
			final SourceAndConverter< T > soc = new SourceAndConverter<>( source, ColorTableUtils.createChannelConverterToARGB( type, dataset, c ), vsoc );
			sources.add( soc );
		}
	}

	// TODO note imaris conventions in javadoc
	// uses Imaris conventions for min
	public void setCalibration(
			final String unit,
			final float extendMinX,
			final float extendMaxX,
			final float extendMinY,
			final float extendMaxY,
			final float extendMinZ,
			final float extendMaxZ ) throws Error // TODO: revise exception handling
	{
		final int sx = dataset.GetSizeX();
		final int sy = dataset.GetSizeY();
		final int sz = dataset.GetSizeZ();

		final double[] calib = new double[] {
				( extendMaxX - extendMinX ) / sx,
				( extendMaxY - extendMinY ) / sy,
				( extendMaxZ - extendMinZ ) / sz
		};
		voxelDimensions.set( unit, calib );

		// --------------------------------------------------------------------
		// update Sources

		// The "+ calib[ d ] / 2" is for deal with Imaris having min at pixel
		// corner, ImgLib having min at pixel center
		final double minX = extendMinX + calib[ 0 ] / 2;
		final double minY = extendMinY + calib[ 1 ] / 2;
		final double minZ = extendMinZ + calib[ 2 ] / 2;

		for ( SourceAndConverter< T > soc : sources )
		{
			final AbstractImarisSource< ? > source = ( AbstractImarisSource< ? > ) soc.getSpimSource();
			final AbstractImarisSource< ? > volatileSource = ( AbstractImarisSource< ? > ) soc.asVolatile().getSpimSource();
			source.setCalibration( voxelDimensions, minX, minY, minZ );
			volatileSource.setCalibration( voxelDimensions, minX, minY, minZ );
		}


		updateImpAxes();

		// --------------------------------------------------------------------
		// update Imaris extends

		ImarisUtils.setVoxelDimensions( dataset, unit, extendMinX, extendMaxX, extendMinY, extendMaxY, extendMinZ, extendMaxZ );
	}

	private void updateImpColorTables() throws Error
	{
		final int sz = dataset.GetSizeZ();
		final int sc = dataset.GetSizeC();
		final int st = dataset.GetSizeT();

		imp.initializeColorTables( sc * sz * st );
		for ( int c = 0; c < sc; ++c )
		{
			final ColorTable8 cT = ColorTableUtils.createChannelColorTable( dataset, c );
			for ( int t = 0; t < st; ++t )
				for ( int z = 0; z < sz; ++z )
					imp.setColorTable( cT, z + sz * ( c + sc * t ) );
		}
	}

	private void updateImpAxes()
	{
		final ArrayList< CalibratedAxis > axes = new ArrayList<>();
		axes.add( new DefaultLinearAxis( Axes.X, voxelDimensions.unit(), voxelDimensions.dimension( 0 ) ) );
		axes.add( new DefaultLinearAxis( Axes.Y, voxelDimensions.unit(), voxelDimensions.dimension( 1 ) ) );
		if ( axisOrder().hasZ() )
			axes.add( new DefaultLinearAxis( Axes.Z, voxelDimensions.unit(), voxelDimensions.dimension( 2 ) ) );
		if ( axisOrder().hasChannels() )
			axes.add( new DefaultLinearAxis( Axes.CHANNEL ) );
		if ( axisOrder().hasTimepoints() )
			axes.add( new DefaultLinearAxis( Axes.TIME ) );

		for ( int i = 0; i < axes.size(); ++i )
			imp.setAxis( axes.get( i ), i );
	}

	// doesn't change the min
	public void setCalibration( final VoxelDimensions voxelDimensions)
	{
		// TODO
		throw new UnsupportedOperationException();
	}

	// uses ImgLib2 conventions for min
	public void setCalibration( final VoxelDimensions voxelDimensions,
			final double minX,
			final double minY,
			final double minZ )
	{
		// TODO
		throw new UnsupportedOperationException();
	}

	/**
	 * Get the full resolution image.
	 * The image is a {@code CachedCellImg} which loads blocks from Imaris.
	 */
	public < A > Img< T > getImage() // TODO: rename to getImg()
	{
		return imagePyramid.getImg( 0 );
	}

	/**
	 * Get {@code ImgPlus} wrapping full resolution image (see {@link #getImage}).
	 * Metadata and color tables are set up according to Imaris
	 * (at the time of construction of this {@code ImarisDataset}).
	 */
	public ImgPlus< T > getImgPlus()
	{
		return imp;
	}

	/**
	 * Get the list of sources, one for each channel of the dataset.
	 * The sources provide nested volatile versions.
	 * The sources are multi-resolution, reflecting the resolution pyramid of the Imaris dataset.
	 */
	public List< SourceAndConverter< T > > getSources()
	{
		return sources;
	}

	/**
	 * Get the {@code SharedQueue} used for asynchronous loading of blocks from Imaris.
	 */
	public SharedQueue getSharedQueue()
	{
		return imagePyramid.getSharedQueue();
	}

	/**
	 * Get the number of levels in the resolution pyramid.
	 */
	public int numResolutions()
	{
		return imagePyramid.numResolutions();
	}

	/**
	 * Get the number channels.
	 */
	public int numChannels()
	{
		return imagePyramid.numChannels();
	}

	/**
	 * Get the number timepoints.
	 */
	public int numTimepoints()
	{
		return imagePyramid.numTimepoints();
	}

	/**
	 * Get an instance of the pixel type.
	 */
	public T getType()
	{
		return imagePyramid.getType();
	}

	/**
	 * Get the axis order of this dataset.
	 *
	 * Note that Dimensions of size 1 are stripped from the dataset.
	 * So a single-channel, single-timepoint image might have axis order {@code XYZ}.
	 */
	public AxisOrder axisOrder()
	{
		return imagePyramid.axisOrder();
	}

	/**
	 * Get the physical calibration: size of voxel in X,Y,Z
	 */
	public VoxelDimensions getVoxelDimensions()
	{
		return voxelDimensions;
	}

	/**
	 * Get the base color of a channel.
	 *
	 * @param channel index of the channel
	 * @return channel color
	 */
	public ARGBType getChannelColor( final int channel ) throws Error
	{
		return ColorTableUtils.getChannelColor( dataset, channel );
	}

	/**
	 * Get the "Image > Filename" parameter of the dataset.
	 */
	public String getFilename() throws Error
	{
		return dataset.GetParameter( "Image", "Filename" );
	}

	/**
	 * Get the "Image > Name" parameter of the dataset.
	 */
	public String getName() throws Error
	{
		return dataset.GetParameter("Image", "Name");
	}

	/**
	 * Get the underlying {@code IDataSet} ICE proxy.
	 */
	public IDataSetPrx getIDataSetPrx()
	{
		return dataset;
	}

	/**
	 * TODO
	 *
	 * Persist changes back to Imaris.
	 * Note that only the full resolution (level 0) image is writable!
	 */
	public void persist()
	{
		this.imagePyramid.persist();
	}

	/**
	 * TODO
	 */
	public void invalidatePyramid() // TODO: rename!?
	{
		this.imagePyramid.invalidate();
	}
}
