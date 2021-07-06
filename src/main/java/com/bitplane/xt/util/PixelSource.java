package com.bitplane.xt.util;

import Imaris.Error;
import Imaris.IDataSetPrx;
import Imaris.tType;
import java.util.function.IntFunction;
import net.imglib2.img.basictypeaccess.volatiles.array.DirtyVolatileByteArray;
import net.imglib2.img.basictypeaccess.volatiles.array.DirtyVolatileFloatArray;
import net.imglib2.img.basictypeaccess.volatiles.array.DirtyVolatileShortArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileByteArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileFloatArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileShortArray;

import static com.bitplane.xt.util.MapIntervalDimension.mapIntervalDimension;

/**
 * Reading Imaris blocks as primitive arrays
 *
 * @param <A>
 *     TODO
 *     {@code byte[]}
 *     {@code VolatileByteArray}
 *     {@code DirtyVolatileByteArray}
 *     {@code short[]}
 *     {@code VolatileShortArray}
 *     {@code DirtyVolatileShortArray}
 *     {@code float[]}
 *     {@code VolatileFloatArray}
 *     {@code DirtyVolatileFloatArray}
 */
@FunctionalInterface
public interface PixelSource< A >
{
	/**
	 * Get sub-volume as flattened array.
	 *
	 * @param level
	 * 		resolution level (0 is full resolution).
	 * @param min
	 * 		minimum of interval in {@code Img} space.
	 * 		Will be augmented to 5D if necessary.
	 * @param size
	 * 		size of interval in {@code Img} space.
	 * 		Will be augmented to 5D if necessary.
	 *
	 * @return the flattened data array.
	 */
	A get( int level, long[] min, int[] size ) throws Error;


	/**
	 * TODO
	 *
	 *
	 * @param dataset
	 * @param datasetType
	 * @param mapDimensions
	 * 		maps Imaris dimension indices to imglib2 dimension indices.
	 * 		If {@code i} is dimension index from Imaris (0..4 means
	 * 		X,Y,Z,C,T) then {@code mapDimensions[i]} is the corresponding
	 * 		dimension in {@code Img}. For {@code Img} dimensions with size=1
	 * 		are skipped. E.g., for a X,Y,C image {@code mapDimensions =
	 *  	{0,1,-1,2,-1}}.
	 *
	 * @return
	 */
	static PixelSource< ? > primitiveArraySource( final IDataSetPrx dataset, final tType datasetType, final int[] mapDimensions )
	{
		final GetDataSubVolume slice = GetDataSubVolume.forDataSet( dataset, datasetType );

		final IntFunction< Object > creator;
		switch ( datasetType )
		{
		case eTypeUInt8:
			creator = byte[]::new;
			break;
		case eTypeUInt16:
			creator = short[]::new;
			break;
		case eTypeFloat:
			creator = float[]::new;
			break;
		default:
			throw new IllegalArgumentException();
		}

		final MapIntervalDimension x = mapIntervalDimension( mapDimensions[ 0 ] );
		final MapIntervalDimension y = mapIntervalDimension( mapDimensions[ 1 ] );
		final MapIntervalDimension z = mapIntervalDimension( mapDimensions[ 2 ] );
		final MapIntervalDimension c = mapIntervalDimension( mapDimensions[ 3 ] );
		final MapIntervalDimension t = mapIntervalDimension( mapDimensions[ 4 ] );

		return ( r, min, size ) -> {
			final int ox = x.min( min );
			final int oy = y.min( min );
			final int oz = z.min( min );
			final int oc = c.min( min );
			final int ot = t.min( min );

			final int sx = x.size( size );
			final int sy = y.size( size );
			final int sz = z.size( size );
			final int sc = c.size( size );
			final int st = t.size( size );

			if ( sc == 1 && st == 1 )
				return slice.get( ox, oy, oz, oc, ot, r, sx, sy, sz );
			else
			{
				final Object data = creator.apply( sx * sy * sz * sc * st );
				final int slicelength = sx * sy * sz;
				for ( int dt = 0; dt < st; ++dt )
				{
					for ( int dc = 0; dc < sc; ++dc )
					{
						final Object slicedata = slice.get( ox, oy, oz, oc + dc, ot + dt, r, sx, sy, sz );
						final int destpos = ( dt * sc + dc ) * slicelength;
						System.arraycopy( slicedata, 0, data, destpos, slicelength );
					}
				}
				return data;
			}
		};
	}

	static < A > PixelSource< A > volatileArraySource(
			final IDataSetPrx dataset,
			final tType datasetType,
			final int[] mapDimensions,
			final boolean withDirtyFlag )
	{
		final PixelSource< ? > pixels = primitiveArraySource(dataset, datasetType, mapDimensions );
		if ( withDirtyFlag )
		{
			switch ( datasetType )
			{
			case eTypeUInt8:
				return ( r, min, size ) -> ( A ) new DirtyVolatileByteArray( ( byte[] ) ( pixels.get( r, min, size ) ), true );
			case eTypeUInt16:
				return ( r, min, size ) -> ( A ) new DirtyVolatileShortArray( ( short[] ) ( pixels.get( r, min, size ) ), true );
			case eTypeFloat:
				return ( r, min, size ) -> ( A ) new DirtyVolatileFloatArray( ( float[] ) ( pixels.get( r, min, size ) ), true );
			default:
				throw new IllegalArgumentException();
			}
		}
		else
		{
			switch ( datasetType )
			{
			case eTypeUInt8:
				return ( r, min, size ) -> ( A ) new VolatileByteArray( ( byte[] ) ( pixels.get( r, min, size ) ), true );
			case eTypeUInt16:
				return ( r, min, size ) -> ( A ) new VolatileShortArray( ( short[] ) ( pixels.get( r, min, size ) ), true );
			case eTypeFloat:
				return ( r, min, size ) -> ( A ) new VolatileFloatArray( ( float[] ) ( pixels.get( r, min, size ) ), true );
			default:
				throw new IllegalArgumentException();
			}
		}
	}

}
